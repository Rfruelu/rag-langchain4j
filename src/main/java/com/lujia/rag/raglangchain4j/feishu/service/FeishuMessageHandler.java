package com.lujia.rag.raglangchain4j.feishu.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lujia.rag.raglangchain4j.chat.entity.RagChatMessage;
import com.lujia.rag.raglangchain4j.chat.service.RagChatService;
import com.lujia.rag.raglangchain4j.feishu.model.FeishuEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 飞书消息处理核心
 * <p>接收飞书事件 → 解析消息文本 → 调用 RAG 服务 → 构建卡片 → 发送回复</p>
 * <p>支持单聊和群聊模式，群聊中需要 @机器人 才触发回复</p>
 */
@Slf4j
@Service
public class FeishuMessageHandler {

    /** 飞书内部员工用户类型 */
    private static final String FEISHU_USER_TYPE = "STAFF";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private RagChatService ragChatService;

    @Autowired
    private FeishuConversationService conversationService;

    @Autowired
    private FeishuCardBuilder cardBuilder;

    @Autowired
    private FeishuApiService apiService;

    /**
     * 处理飞书消息事件
     * <p>在虚拟线程中异步执行，避免阻塞飞书事件回调线程</p>
     *
     * @param event 飞书事件
     */
    public void handleMessage(FeishuEvent event) {
        // 使用虚拟线程异步处理，不阻塞飞书事件回调
        Thread.startVirtualThread(() -> doHandleMessage(event));
    }

    /**
     * 实际的消息处理逻辑
     */
    private void doHandleMessage(FeishuEvent event) {
        try {
            FeishuEvent.EventBody body = event.getEvent();
            if (body == null || body.message() == null) {
                log.warn("飞书事件体为空，跳过处理");
                return;
            }

            FeishuEvent.Message message = body.message();
            FeishuEvent.Sender sender = body.sender();

            String chatId = message.chatId();
            String chatType = message.chatType();
            String messageId = message.messageId();
            String openId = sender != null && sender.senderId() != null
                    ? sender.senderId().openId() : null;

            // 1. 群聊中检查是否 @了机器人，未 @则不回复
            if ("group".equals(chatType) && !isMentionedBot(message, event.getHeader())) {
                log.debug("群聊消息未 @机器人，跳过: chatId={}", chatId);
                return;
            }

            // 2. 解析用户消息文本
            String userText = extractText(message);
            if (userText == null || userText.isBlank()) {
                apiService.replyCardMessage(messageId,
                        cardBuilder.buildSimpleCard("请输入文字内容，我来帮你查询知识库 😊"));
                return;
            }

            // 3. 处理特殊命令
            if (handleSpecialCommands(userText.trim(), chatId, chatType, openId, messageId)) {
                return;
            }

            // 4. 获取或创建会话
            String conversationId = conversationService.getOrCreateConversation(chatId, chatType, openId);

            // 5. 调用 RAG 服务获取 AI 回复（飞书用户统一为 STAFF）
            log.info("飞书消息处理开始: chatId={}, conversationId={}, userText={}",
                    chatId, conversationId, userText.substring(0, Math.min(50, userText.length())));

            RagChatMessage aiMessage = ragChatService.sendMessage(conversationId, userText, FEISHU_USER_TYPE);

            // 6. 解析 RAG 引用文档
            List<Map<String, Object>> references = parseReferences(aiMessage.getRagReferences());

            // 7. 构建飞书卡片并回复
            String cardJson = cardBuilder.buildRagCard(aiMessage.getContent(), references);
            apiService.replyCardMessage(messageId, cardJson);

            log.info("飞书消息处理完成: chatId={}, messageId={}", chatId, messageId);

        } catch (Exception e) {
            log.error("飞书消息处理异常", e);
            try {
                String errorMsg = "抱歉，处理消息时出现异常，请稍后重试 😥";
                apiService.replyCardMessage(event.getEvent().message().messageId(),
                        cardBuilder.buildSimpleCard(errorMsg));
            } catch (Exception ex) {
                log.error("发送错误回复失败", ex);
            }
        }
    }

    /**
     * 从飞书消息中提取纯文本内容
     * <p>飞书文本消息的 content 格式为 JSON：{"text":"@机器人 用户消息内容"}</p>
     *
     * @param message 飞书消息
     * @return 提取的文本内容
     */
    private String extractText(FeishuEvent.Message message) {
        if (message == null || message.content() == null) {
            return null;
        }

        try {
            String content = message.content();
            String messageType = message.messageType();

            if ("text".equals(messageType)) {
                JsonNode json = objectMapper.readTree(content);
                String text = json.has("text") ? json.get("text").asText() : "";
                // 去除 @机器人 的前缀（飞书会在文本中包含 @bot_name）
                text = text.replaceAll("@\\S+\\s*", "").trim();
                return text;
            }

            // 其他消息类型暂不支持
            log.info("不支持的消息类型: {}", messageType);
            return null;

        } catch (Exception e) {
            log.warn("解析飞书消息内容失败: {}", message.content(), e);
            return null;
        }
    }

    /**
     * 检查群聊消息中是否 @了机器人
     * <p>通过检查 mentions 字段中是否包含机器人的 open_id 来判断</p>
     */
    private boolean isMentionedBot(FeishuEvent.Message message, FeishuEvent.EventHeader header) {
        String mentionsJson = message.mentions();
        if (mentionsJson == null || mentionsJson.isBlank()) {
            return false;
        }

        try {
            List<FeishuEvent.Mention> mentions = objectMapper.readValue(
                    mentionsJson, new TypeReference<List<FeishuEvent.Mention>>() {});
            if (mentions == null || mentions.isEmpty()) {
                return false;
            }
            // 群聊中有 mentions 说明 @了某人，简单处理：有 mentions 就认为 @了机器人
            // （因为飞书机器人只会收到 @自己 的事件）
            return true;
        } catch (Exception e) {
            log.warn("解析 mentions 失败: {}", mentionsJson, e);
            return false;
        }
    }

    /**
     * 处理特殊命令（如重置会话等）
     *
     * @return true 表示已处理，无需继续 RAG 流程
     */
    private boolean handleSpecialCommands(String text, String chatId, String chatType,
                                          String openId, String messageId) {
        // /reset 或 /新对话：重置当前会话
        if ("/reset".equals(text) || "/新对话".equals(text) || "新对话".equals(text)) {
            conversationService.clearMapping(chatId);
            apiService.replyCardMessage(messageId,
                    cardBuilder.buildSimpleCard("✅ 会话已重置，请开始新的对话吧！"));
            return true;
        }

        // /help 或 /帮助：显示帮助信息
        if ("/help".equals(text) || "/帮助".equals(text)) {
            String helpText = """
                    **📚 RAG 智能助手 - 使用帮助**
                    
                    直接输入问题即可查询知识库，我会为你找到相关答案。
                    
                    **快捷命令：**
                    1. **新对话** / `/reset` - 重置当前会话
                    2. **帮助** / `/help` - 显示本帮助信息
                    
                    **支持功能：**
                    - 知识库问答（产品咨询、售后问题、价格查询等）
                    - 多轮对话（支持上下文理解）
                    - 图片展示（知识库文档中的图片）
                    - 引用文档（展示答案来源）""";
            apiService.replyCardMessage(messageId, cardBuilder.buildSimpleCard(helpText));
            return true;
        }

        return false;
    }

    /**
     * 解析 RAG 引用文档 JSON
     *
     * @param referencesJson 引用文档 JSON 字符串
     * @return 引用文档列表
     */
    private List<Map<String, Object>> parseReferences(String referencesJson) {
        if (referencesJson == null || referencesJson.isBlank() || "[]".equals(referencesJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(referencesJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("解析 RAG 引用文档失败: {}", referencesJson, e);
            return null;
        }
    }
}
