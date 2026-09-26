package com.lujia.rag.raglangchain4j.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lujia.rag.raglangchain4j.chat.entity.RagChatConversation;
import com.lujia.rag.raglangchain4j.chat.entity.RagChatMessage;
import com.lujia.rag.raglangchain4j.chat.enums.ChatIntent;
import com.lujia.rag.raglangchain4j.chat.mapper.RagChatConversationMapper;
import com.lujia.rag.raglangchain4j.chat.mapper.RagChatMessageMapper;
import com.lujia.rag.raglangchain4j.chat.rag.DbBackedChatMemory;
import com.lujia.rag.raglangchain4j.chat.rag.ChatMemoryCache;
import com.lujia.rag.raglangchain4j.chat.rag.IntentContextHolder;
import com.lujia.rag.raglangchain4j.chat.rag.RagStatusContext;
import com.lujia.rag.raglangchain4j.chat.rag.ReferenceContext;
import com.lujia.rag.raglangchain4j.chat.service.IntentRecognitionService;
import com.lujia.rag.raglangchain4j.auth.service.AuthService;
import com.lujia.rag.raglangchain4j.auth.entity.UserInfo;
import com.lujia.rag.raglangchain4j.chat.service.KnowEngineChatAiService;
import com.lujia.rag.raglangchain4j.chat.service.RagChatService;
import com.lujia.rag.raglangchain4j.chat.service.TitleGeneratorService;
import com.lujia.rag.raglangchain4j.common.metrics.RagMetrics;
import com.lujia.rag.raglangchain4j.common.ratelimit.ChatRateLimiter;
import cn.hutool.core.util.IdUtil;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * AI对话服务实现
 * <p>封装会话管理、消息收发、意图识别与路由等业务逻辑</p>
 */
@Slf4j
@Service
public class RagChatServiceImpl extends ServiceImpl<RagChatConversationMapper, RagChatConversation>
        implements RagChatService {

    /** Redis 自增 key 前缀：会话ID */
    private static final String CONVERSATION_ID_KEY = "chat:conversation:seq";

    /** Redis 自增 key 前缀：消息ID */
    private static final String MESSAGE_ID_KEY = "chat:message:seq";

    /** 通用闲聊加载的历史消息轮数（每轮 = 1条用户消息 + 1条AI回复） */
    private static final int GENERAL_CHAT_HISTORY_TURNS = 10;

    @Autowired
    private RagChatMessageMapper messageMapper;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private TitleGeneratorService titleGeneratorService;

    @Autowired
    private IntentRecognitionService intentRecognitionService;

    @Autowired
    private StreamingChatModel generalChatModel;

    @Autowired
    private KnowEngineChatAiService knowEngineChatAiService;

    @Autowired
    private AuthService authService;

    @Autowired
    private ChatRateLimiter chatRateLimiter;

    @Autowired
    private RagMetrics ragMetrics;

    @Autowired
    private ChatMemoryCache chatMemoryCache;

    /**
     * 获取当前登录用户的类型
     * <p>从 Sa-Token Session 中获取用户信息，返回用户类型字符串</p>
     *
     * @return 用户类型（VISITOR/CUSTOMER/STAFF），未登录时返回 VISITOR
     */
    private String getCurrentUserType() {
        try {
            UserInfo user = authService.getCurrentUser();
            if (user != null && user.getUserType() != null) {
                return user.getUserType();
            }
        } catch (Exception e) {
            log.warn("获取当前用户类型失败，默认 VISITOR: {}", e.getMessage());
        }
        return "VISITOR";
    }

    /**
     * 生成会话唯一标识
     * <p>格式：conv_{UUID前8位}_{Redis自增序号}</p>
     */
    private String generateConversationId() {
        String uuidPart = IdUtil.fastSimpleUUID().substring(0, 8);
        RAtomicLong atomicLong = redissonClient.getAtomicLong(CONVERSATION_ID_KEY);
        long seq = atomicLong.incrementAndGet();
        return "conv_" + uuidPart + "_" + seq;
    }

    /**
     * 生成消息唯一标识
     * <p>格式：msg_{UUID前8位}_{Redis自增序号}</p>
     */
    private String generateMessageId() {
        String uuidPart = IdUtil.fastSimpleUUID().substring(0, 8);
        RAtomicLong atomicLong = redissonClient.getAtomicLong(MESSAGE_ID_KEY);
        long seq = atomicLong.incrementAndGet();
        return "msg_" + uuidPart + "_" + seq;
    }

    /**
     * 创建新会话
     */
    @Override
    public RagChatConversation createConversation(String userId, String title) {
        RagChatConversation conversation = new RagChatConversation();
        conversation.setConversationId(generateConversationId());
        conversation.setUserId(userId != null ? userId : "default");
        conversation.setTitle(title != null ? title : "新对话");
        conversation.setStatus("active");
        save(conversation);
        log.info("创建新会话: conversationId={}, userId={}", conversation.getConversationId(), userId);
        return conversation;
    }

    /**
     * 查询用户的会话列表
     */
    @Override
    public List<RagChatConversation> listConversations(String userId) {
        LambdaQueryWrapper<RagChatConversation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RagChatConversation::getUserId, userId != null ? userId : "default")
                .orderByDesc(RagChatConversation::getUpdatedTime);
        return list(wrapper);
    }

    /**
     * 获取指定会话的消息列表
     */
    @Override
    public List<RagChatMessage> listMessages(String conversationId) {
        LambdaQueryWrapper<RagChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RagChatMessage::getConversationId, conversationId)
                .orderByAsc(RagChatMessage::getCreatedTime);
        return messageMapper.selectList(wrapper);
    }

    /**
     * 发送消息并获取AI回复（同步）
     * <p>流程：保存用户消息 → 意图识别 → 生成回复 → 保存AI回复 → 更新会话标题</p>
     * <p>Web 端调用，通过 Sa-Token 获取当前用户类型</p>
     */
    @Override
    public RagChatMessage sendMessage(String conversationId, String content) {
        return sendMessage(conversationId, content, getCurrentUserType());
    }

    /**
     * 发送消息并获取AI回复（支持外部指定用户类型）
     * <p>供飞书机器人等非 Sa-Token 认证入口调用，直接使用传入的 userType 进行检索权限过滤</p>
     */
    @Override
    public RagChatMessage sendMessage(String conversationId, String content, String userType) {
        // 0. 按用户/会话限流，防止恶意刷量
        chatRateLimiter.checkChatAllowed(conversationId);

        // 1. 校验会话是否存在
        RagChatConversation conversation = getConversationByBizId(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("会话不存在: " + conversationId);
        }

        // 2. 保存用户消息
        RagChatMessage userMessage = new RagChatMessage();
        userMessage.setMessageId(generateMessageId());
        userMessage.setConversationId(conversationId);
        userMessage.setType("USER");
        userMessage.setContent(content);
        userMessage.setTokenCount(estimateTokenCount(content));
        messageMapper.insert(userMessage);
        chatMemoryCache.evict(conversationId);
        log.info("用户消息已保存: messageId={}, conversationId={}", userMessage.getMessageId(), conversationId);

        // 3. 意图识别（结合多轮对话上下文）
        ChatIntent intent = recognizeIntentSafely(conversationId, content);

        // 4. 根据意图生成回复
        String assistantContent;
        String modelName;
        String referencesJson = null;
        if (intent == ChatIntent.GENERAL) {
            // 通用闲聊：加载历史上下文，构建多轮对话消息列表
            List<ChatMessage> messages = buildGeneralChatMessages(conversationId, content);
            assistantContent = chatWithGeneralModel(messages);
            modelName = "general-chat";
        } else {
            // RAG 业务问答：设置引用上下文，收集检索引用的文档信息
            ReferenceContext.set(new ArrayList<>());
            // 设置 ThreadLocal，供 RAG 管道内组件读取（查询改写加载历史、路由器复用意图、权限过滤）
            IntentContextHolder.setIntent(intent);
            IntentContextHolder.setConversationId(conversationId);
            IntentContextHolder.setUserType(userType);
            try {
                assistantContent = knowEngineChatAiService.chat(conversationId, content);
                modelName = "rag-business";
                // 引用信息在清理 ThreadLocal 前读取
                referencesJson = ReferenceContext.getReferencesJson();
            } finally {
                // 清理 ThreadLocal，防止线程复用导致数据污染
                IntentContextHolder.clear();
                RagStatusContext.clear();
                ReferenceContext.clear();
            }
        }

        // 5. 保存AI回复消息
        RagChatMessage assistantMessage = new RagChatMessage();
        assistantMessage.setMessageId(generateMessageId());
        assistantMessage.setConversationId(conversationId);
        assistantMessage.setType("ASSISTANT");
        assistantMessage.setContent(assistantContent);
        assistantMessage.setModelName(modelName);
        assistantMessage.setTokenCount(estimateTokenCount(assistantContent));
        // 保存 RAG 引用文档信息
        if (referencesJson != null) {
            assistantMessage.setRagReferences(referencesJson);
        }
        messageMapper.insert(assistantMessage);
        chatMemoryCache.evict(conversationId);
        log.info("AI回复已保存: messageId={}, conversationId={}", assistantMessage.getMessageId(), conversationId);

        // 6. 更新会话标题（首次对话时，用用户消息的前20个字符作为标题）
        if ("新对话".equals(conversation.getTitle())) {
            String newTitle = content.length() > 20 ? content.substring(0, 20) + "..." : content;
            conversation.setTitle(newTitle);
        }
        // updatedTime 置空，交由 MyMetaObjectHandler 自动填充，使会话排到列表前面
        conversation.setUpdatedTime(null);
        updateById(conversation);

        return assistantMessage;
    }

    /**
     * 流式发送消息并返回AI回复
     * <p>流程：保存用户消息 → 意图识别 → 根据意图路由到通用聊天或业务回复 → SseEmitter 流式输出</p>
     */
    @Override
    public SseEmitter sendMessageStream(String conversationId, String content) {
        // 0. 按用户/会话限流，防止恶意刷量
        chatRateLimiter.checkChatAllowed(conversationId);

        // 1. 校验会话是否存在
        RagChatConversation conversation = getConversationByBizId(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("会话不存在: " + conversationId);
        }

        // 2. 保存用户消息
        RagChatMessage userMessage = new RagChatMessage();
        userMessage.setMessageId(generateMessageId());
        userMessage.setConversationId(conversationId);
        userMessage.setType("USER");
        userMessage.setContent(content);
        userMessage.setTokenCount(estimateTokenCount(content));
        messageMapper.insert(userMessage);
        chatMemoryCache.evict(conversationId);
        log.info("用户消息已保存: messageId={}, conversationId={}", userMessage.getMessageId(), conversationId);

        // 3. 意图识别（结合多轮对话上下文）
        ChatIntent intent = recognizeIntentSafely(conversationId, content);
        log.info("意图识别结果: intent={}, conversationId={}", intent, conversationId);

        // 4. 创建 SseEmitter（超时 5 分钟）
        SseEmitter emitter = new SseEmitter(300_000L);

        // 5. 根据意图路由到不同的流式回复
        if (intent == ChatIntent.GENERAL) {
            streamGeneralChat(emitter, content, conversationId, conversation);
        } else {
            streamRagChat(emitter, content, conversationId, conversation, intent);
        }

        return emitter;
    }

    /**
     * 安全执行意图识别，失败时降级为 GENERAL
     * <p>传入 conversationId 使框架自动加载多轮对话上下文，帮助理解指代性表述</p>
     */
    private ChatIntent recognizeIntentSafely(String conversationId, String content) {
        try {
            ChatIntent intent = intentRecognitionService.recognizeIntent(conversationId, content);
            ragMetrics.recordIntent(intent.name());
            return intent;
        } catch (Exception e) {
            log.warn("意图识别失败，使用默认意图 GENERAL: conversationId={}, error={}", conversationId, e.getMessage());
            ragMetrics.recordIntent(ChatIntent.GENERAL.name());
            return ChatIntent.GENERAL;
        }
    }

    /**
     * 通用闲聊流式输出
     * <p>使用通用流式模型（generalChatModel）逐 token 流式回复非业务相关的用户消息，
     * 加载历史对话上下文实现多轮对话，通过 SseEmitter 显式发送 SSE 事件，确保每个 token 立即刷新到客户端</p>
     */
    private void streamGeneralChat(SseEmitter emitter, String content, String conversationId,
                                   RagChatConversation conversation) {
        // 使用虚拟线程异步执行，避免阻塞请求线程
        Thread.startVirtualThread(() -> {
            try {
                // 加载历史上下文，构建多轮对话消息列表
                List<ChatMessage> messages = buildGeneralChatMessages(conversationId, content);

                StringBuilder fullContent = new StringBuilder();

                generalChatModel.chat(messages, new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String token) {
                        fullContent.append(token);
                        try {
                            emitter.send(SseEmitter.event().data(token));
                        } catch (Exception e) {
                            log.debug("发送 SSE 事件失败: {}", e.getMessage());
                        }
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        try {
                            // 发送完成元数据事件
                            String messageId = generateMessageId();
                            emitter.send(SseEmitter.event().name("done")
                                    .data("{\"messageId\":\"" + messageId
                                            + "\",\"modelName\":\"general-chat\",\"tokenCount\":"
                                            + estimateTokenCount(fullContent.toString()) + "}"));

                            // 保存AI回复消息
                            RagChatMessage assistantMessage = new RagChatMessage();
                            assistantMessage.setMessageId(messageId);
                            assistantMessage.setConversationId(conversationId);
                            assistantMessage.setType("ASSISTANT");
                            assistantMessage.setContent(fullContent.toString());
                            assistantMessage.setModelName("general-chat");
                            assistantMessage.setTokenCount(estimateTokenCount(fullContent.toString()));
                            messageMapper.insert(assistantMessage);
                            chatMemoryCache.evict(conversationId);
                            log.info("通用聊天回复已保存: messageId={}, conversationId={}", messageId, conversationId);

                            // 更新会话的修改时间，使其排到列表前面
                            lambdaUpdate()
                                    .eq(RagChatConversation::getConversationId, conversationId)
                                    .set(RagChatConversation::getUpdatedTime, java.time.LocalDateTime.now())
                                    .update();

                            if ("新对话".equals(conversation.getTitle())) {
                                Thread.startVirtualThread(() -> generateAndUpdateTitle(
                                        conversationId, content, fullContent.toString()));
                            }
                        } catch (Exception e) {
                            log.error("保存通用聊天回复失败", e);
                        } finally {
                            emitter.complete();
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error("通用聊天流式响应错误", error);
                        emitter.completeWithError(error);
                    }
                });
            } catch (Exception e) {
                log.error("通用聊天调用失败", e);
                emitter.completeWithError(e);
            }
        });
    }

    /**
     * RAG 检索增强流式回复
     * <p>调用 KnowEngineChatAiService（内部走 RetrievalAugmentor：查询改写 → 意图路由 → 混合检索 → LLM 生成），
     * 逐 token 流式输出，完成后保存消息并更新会话</p>
     */
    private void streamRagChat(SseEmitter emitter, String content, String conversationId,
                               RagChatConversation conversation, ChatIntent intent) {
        // 使用虚拟线程异步执行，避免阻塞请求线程
        Thread.startVirtualThread(() -> {
            // 在异步线程中设置 ThreadLocal，供 RAG 管道组件使用
            IntentContextHolder.setIntent(intent);
            IntentContextHolder.setConversationId(conversationId);
            IntentContextHolder.setUserType(getCurrentUserType());
    
            // 用于在 onComplete 回调中保存引用信息
            final String[] referencesJsonHolder = {"[]"};
    
            try {
                // 设置 RAG 管道状态回调，使各组件能够向前端推送实时处理进度
                RagStatusContext.set(statusJson -> {
                    try {
                        emitter.send(SseEmitter.event().data(statusJson));
                    } catch (Exception e) {
                        log.debug("发送 RAG 状态事件失败: {}", e.getMessage());
                    }
                });
    
                // 初始化引用文档上下文，收集 RAG 检索过程中引用的文档信息
                ReferenceContext.set(new ArrayList<>());
    
                StringBuilder fullContent = new StringBuilder();
    
                // 调用 streamChat 触发 RAG 管道同步执行（查询改写 → 意图识别 → 知识检索）
                // 管道内各组件通过 RagStatusContext 发射状态事件
                knowEngineChatAiService.streamChat(conversationId, content)
                        .subscribe(
                                token -> {
                                    fullContent.append(token);
                                    try {
                                        emitter.send(SseEmitter.event().data(token));
                                    } catch (Exception e) {
                                        log.debug("发送 SSE token 失败: {}", e.getMessage());
                                    }
                                },
                                error -> {
                                    log.error("RAG 流式响应错误: conversationId={}", conversationId, error);
                                    emitter.completeWithError(error);
                                },
                                () -> {
                                    try {
                                        // 发送 RAG 引用文档事件
                                        if (!"[]".equals(referencesJsonHolder[0])) {
                                            emitter.send(SseEmitter.event().data("[RAG_REFERENCES]" + referencesJsonHolder[0]));
                                        }
    
                                        // 发送完成元数据事件
                                        String messageId = generateMessageId();
                                        emitter.send(SseEmitter.event().name("done")
                                                .data("{\"messageId\":\"" + messageId
                                                        + "\",\"modelName\":\"rag-business\",\"tokenCount\":"
                                                        + estimateTokenCount(fullContent.toString()) + "}"));
    
                                        // 保存AI回复消息
                                        RagChatMessage assistantMessage = new RagChatMessage();
                                        assistantMessage.setMessageId(messageId);
                                        assistantMessage.setConversationId(conversationId);
                                        assistantMessage.setType("ASSISTANT");
                                        assistantMessage.setContent(fullContent.toString());
                                        assistantMessage.setModelName("rag-business");
                                        assistantMessage.setTokenCount(estimateTokenCount(fullContent.toString()));
                                        // 保存 RAG 引用文档信息
                                        assistantMessage.setRagReferences(referencesJsonHolder[0]);
                                        messageMapper.insert(assistantMessage);
                                        chatMemoryCache.evict(conversationId);
                                        log.info("RAG 回复已保存: messageId={}, conversationId={}", messageId, conversationId);

                                        // 更新会话的修改时间，使其排到列表前面
                                        lambdaUpdate()
                                                .eq(RagChatConversation::getConversationId, conversationId)
                                                .set(RagChatConversation::getUpdatedTime, java.time.LocalDateTime.now())
                                                .update();
    
                                        if ("新对话".equals(conversation.getTitle())) {
                                            Thread.startVirtualThread(() -> generateAndUpdateTitle(
                                                    conversationId, content, fullContent.toString()));
                                        }
                                    } catch (Exception e) {
                                        log.error("保存 RAG 回复失败", e);
                                    } finally {
                                        emitter.complete();
                                    }
                                }
                        );
    
                // RAG 管道已完成，读取引用文档信息供 onComplete 回调使用
                String referencesJson = ReferenceContext.getReferencesJson();
                referencesJsonHolder[0] = referencesJson;
    
                // 发射"正在生成回答"状态（步骤4/4）
                RagStatusContext.emitStatus(4, 4, "正在生成回答", "基于检索内容生成回复");
            } catch (Exception e) {
                log.error("RAG 流式调用失败", e);
                emitter.completeWithError(e);
            } finally {
                // 清理 ThreadLocal，防止线程复用导致数据污染
                RagStatusContext.clear();
                IntentContextHolder.clear();
                ReferenceContext.clear();
            }
        });
    }



    /**
     * 异步生成会话标题摘要并更新
     * <p>调用千问小模型（qwen-turbo）根据用户消息和AI回复生成简洁标题</p>
     *
     * @param conversationId   会话业务ID
     * @param userContent      用户消息内容
     * @param assistantContent AI回复内容
     */
    private void generateAndUpdateTitle(String conversationId, String userContent, String assistantContent) {
        try {
            // 截取关键内容，避免 token 过多
            String userPart = userContent.length() > 200 ? userContent.substring(0, 200) : userContent;
            String assistantPart = assistantContent.length() > 500 ? assistantContent.substring(0, 500) : assistantContent;

            String title = titleGeneratorService.generateTitle(userPart, assistantPart).trim();
            // 限制标题长度，防止模型输出过长内容
            if (title.length() > 30) {
                title = title.substring(0, 30);
            }

            if (!title.isEmpty()) {
                RagChatConversation conversation = getConversationByBizId(conversationId);
                if (conversation != null) {
                    conversation.setTitle(title);
                    updateById(conversation);
                    log.info("会话标题已更新: conversationId={}, title={}", conversationId, title);
                }
            }
        } catch (Exception e) {
            log.warn("生成会话标题失败，使用默认标题: conversationId={}, error={}", conversationId, e.getMessage());
            // 降级处理：使用用户消息前20个字符作为标题
            RagChatConversation conversation = getConversationByBizId(conversationId);
            if (conversation != null && "新对话".equals(conversation.getTitle())) {
                String fallbackTitle = userContent.length() > 20 ? userContent.substring(0, 20) + "..." : userContent;
                conversation.setTitle(fallbackTitle);
                updateById(conversation);
            }
        }
    }

    /**
     * 删除会话（逻辑删除）
     */
    @Override
    public void deleteConversation(String conversationId) {
        RagChatConversation conversation = getConversationByBizId(conversationId);
        if (conversation != null) {
            removeById(conversation.getId());
            log.info("会话已删除: conversationId={}", conversationId);
        }
    }

    /**
     * 更新会话标题
     */
    @Override
    public void updateConversationTitle(String conversationId, String title) {
        RagChatConversation conversation = getConversationByBizId(conversationId);
        if (conversation != null) {
            conversation.setTitle(title);
            updateById(conversation);
        }
    }

    /**
     * 通过业务 conversationId 查询会话
     */
    private RagChatConversation getConversationByBizId(String conversationId) {
        LambdaQueryWrapper<RagChatConversation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RagChatConversation::getConversationId, conversationId);
        return getOne(wrapper);
    }

    /**
     * 构建通用闲聊的多轮对话消息列表
     * <p>从数据库加载该会话的历史消息，按 SystemMessage + 历史轮次 + 当前用户消息 的顺序组装</p>
     *
     * @param conversationId 会话ID
     * @param currentContent 当前用户消息内容
     * @return 包含历史上下文的消息列表
     */
    private List<ChatMessage> buildGeneralChatMessages(String conversationId, String currentContent) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("你是一个友好的聊天助手。请用自然、亲切的语言与用户交流，回答各类日常问题。"));

        // 从数据库加载该会话的最近历史消息
        List<RagChatMessage> historyMessages = DbBackedChatMemory.loadRecentMessages(
                messageMapper, conversationId, GENERAL_CHAT_HISTORY_TURNS * 2);
        for (RagChatMessage historyMsg : historyMessages) {
            if ("USER".equals(historyMsg.getType())) {
                messages.add(UserMessage.from(historyMsg.getContent()));
            } else if ("ASSISTANT".equals(historyMsg.getType())) {
                messages.add(AiMessage.from(historyMsg.getContent()));
            }
        }

        // 添加当前用户消息
        messages.add(UserMessage.from(currentContent));
        return messages;
    }

    /**
     * 使用通用流式模型执行同步对话（阻塞等待完整响应）
     * <p>模型自身超时时间为 60 秒，此处等待上限略大于模型超时，防止底层挂起导致请求线程无限阻塞</p>
     *
     * @param messages 多轮对话消息列表
     * @return AI回复文本
     */
    private String chatWithGeneralModel(List<ChatMessage> messages) {
        CompletableFuture<String> future = new CompletableFuture<>();
        StringBuilder fullContent = new StringBuilder();
        generalChatModel.chat(messages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                fullContent.append(token);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                future.complete(fullContent.toString());
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });

        try {
            return future.get(90, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("通用聊天响应中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("通用聊天调用失败: " + cause.getMessage(), cause);
        } catch (TimeoutException e) {
            throw new IllegalStateException("通用聊天响应超时", e);
        }
    }

    /**
     * 简单估算 Token 数量（中文约 1.5 字/Token，英文约 4 字符/Token）
     */
    private int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        // 简单估算：中文字符数 + 英文单词数
        int chineseChars = 0;
        int englishWords = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            // UTF-8 编码超过 1 字节的字符按中文字符统计（与原字节长度判断等价，无额外对象分配）
            if (codePoint > 0x7F) {
                chineseChars++;
                inWord = false;
            } else if (Character.isLetterOrDigit(codePoint)) {
                if (!inWord) {
                    englishWords++;
                    inWord = true;
                }
            } else {
                inWord = false;
            }
        }
        return chineseChars + englishWords;
    }
}

