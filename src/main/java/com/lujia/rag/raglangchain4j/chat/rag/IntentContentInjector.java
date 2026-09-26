package com.lujia.rag.raglangchain4j.chat.rag;

import com.lujia.rag.raglangchain4j.chat.enums.ChatIntent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.injector.ContentInjector;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 基于意图识别的动态内容注入器
 * <p>根据 {@link IntentContextHolder} 中的意图，加载对应的提示词模板，
 * 将检索到的内容和用户问题组装后注入到 UserMessage 中</p>
 * <p>提示词模板文件位于 {@code resources/prompt/} 目录下，按意图枚举名命名：</p>
 * <ul>
 *   <li>{@code pre_sales.txt} — 售前咨询</li>
 *   <li>{@code post_sales.txt} — 售后问题</li>
 *   <li>{@code price_inquiry.txt} — 面料价格咨询</li>
 *   <li>{@code system_operation.txt} — 系统操作咨询</li>
 *   <li>{@code general.txt} — 通用闲聊</li>
 * </ul>
 */
@Slf4j
public class IntentContentInjector implements ContentInjector {

    /** 提示词模板目录 */
    private static final String PROMPT_DIR = "/prompt/";

    /** 提示词模板文件后缀 */
    private static final String PROMPT_SUFFIX = ".txt";

    /** 占位符：检索内容 */
    private static final String CONTENTS_PLACEHOLDER = "{{contents}}";

    /** 占位符：用户问题 */
    private static final String USER_MESSAGE_PLACEHOLDER = "{{userMessage}}";

    /** 提示词模板缓存，避免重复读取文件 */
    private final Map<ChatIntent, String> promptCache = new ConcurrentHashMap<>();

    @Override
    public ChatMessage inject(List<Content> contents, ChatMessage chatMessage) {
        // 获取当前意图
        ChatIntent intent = IntentContextHolder.getIntent();
        if (intent == null) {
            log.warn("意图上下文为空，使用默认意图 PRE_SALES");
            intent = ChatIntent.PRE_SALES;
        }

        try {
            // 获取用户原始消息
            String userMessageText = extractUserMessage(chatMessage);

            // 加载对应的提示词模板
            String promptTemplate = loadPromptTemplate(intent);

            // 格式化检索内容
            String formattedContents = formatContents(contents);

            // 替换占位符，生成最终提示词
            String finalPrompt = promptTemplate
                    .replace(CONTENTS_PLACEHOLDER, formattedContents)
                    .replace(USER_MESSAGE_PLACEHOLDER, userMessageText);

            log.debug("意图内容注入完成: intent={}, contentsCount={}", intent, contents.size());
            return UserMessage.from(finalPrompt);
        } finally {
            // 确保 ThreadLocal 在任何情况下都被清理，防止线程复用导致数据污染
            IntentContextHolder.clear();
        }
    }

    /**
     * 从 ChatMessage 中提取用户消息文本
     *
     * @param chatMessage 聊天消息
     * @return 用户消息文本
     */
    private String extractUserMessage(ChatMessage chatMessage) {
        if (chatMessage instanceof UserMessage userMessage) {
            return userMessage.singleText();
        }
        // 其他类型消息使用 toString 作为回退
        return chatMessage.toString();
    }

    /**
     * 加载指定意图的提示词模板
     * <p>首次加载成功后缓存到内存，后续直接使用缓存；加载失败返回默认模板但不缓存，
     * 避免瞬时故障导致默认模板被永久缓存</p>
     *
     * @param intent 用户意图
     * @return 提示词模板文本
     */
    private String loadPromptTemplate(ChatIntent intent) {
        String cached = promptCache.get(intent);
        if (cached != null) {
            return cached;
        }

        String fileName = PROMPT_DIR + intent.name().toLowerCase() + PROMPT_SUFFIX;
        try (InputStream is = getClass().getResourceAsStream(fileName)) {
            if (is == null) {
                log.error("提示词模板文件不存在: {}，使用默认模板", fileName);
                return getDefaultTemplate();
            }
            String template = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));
            promptCache.put(intent, template);
            log.info("提示词模板加载成功: intent={}, file={}", intent, fileName);
            return template;
        } catch (IOException e) {
            log.error("加载提示词模板失败: {}, error={}", fileName, e.getMessage());
            return getDefaultTemplate();
        }
    }

    /**
     * 格式化检索到的内容为文本
     *
     * @param contents 检索内容列表
     * @return 格式化后的文本
     */
    private String formatContents(List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return "（未检索到相关内容）";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < contents.size(); i++) {
            Content content = contents.get(i);
            sb.append("【内容 ").append(i + 1).append("】\n");
            sb.append(content.textSegment().text()).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * 默认提示词模板（当文件加载失败时使用）
     *
     * @return 默认模板
     */
    private String getDefaultTemplate() {
        return """
                你是一个面料撮合交易平台的知识库问答助手。请基于以下检索内容回答用户问题。
                
                以下是检索到的相关内容：
                {{contents}}
                
                用户问题：{{userMessage}}""";
    }
}
