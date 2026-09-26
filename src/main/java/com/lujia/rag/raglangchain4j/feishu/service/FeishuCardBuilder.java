package com.lujia.rag.raglangchain4j.feishu.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lujia.rag.raglangchain4j.feishu.config.FeishuProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 飞书消息卡片构建器
 * <p>将 AI 回复内容（Markdown 文本 + 图片 + 引用文档）构建为飞书 Interactive Card JSON</p>
 * <p>飞书 lark_md 支持部分 Markdown 语法和外部图片 URL，本构建器负责：</p>
 * <ul>
 *   <li>将 AI 回复的 Markdown 文本适配为飞书 lark_md 格式</li>
 *   <li>将 MinIO 内部图片 URL 替换为外部可访问地址</li>
 *   <li>将 RAG 引用文档信息构建为可点击的链接列表</li>
 * </ul>
 */
@Slf4j
@Component
public class FeishuCardBuilder {

    /**
     * Markdown 图片正则：匹配 ![alt](url)
     */
    private static final Pattern IMAGE_PATTERN = Pattern.compile("!\\[([^]]*)]\\(([^)]+)\\)");

    /**
     * 飞书 lark_md 不支持的 Markdown 语法正则（需要清理或转换）
     */
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```(\\w*)\\n([\\s\\S]*?)```");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private FeishuProperties feishuProperties;

    /**
     * 构建 RAG 回复卡片
     *
     * @param aiContent    AI 回复的 Markdown 文本
     * @param references   RAG 引用文档列表（JSON 解析后的 List），可为 null
     * @return 飞书卡片 JSON 字符串
     */
    public String buildRagCard(String aiContent, List<Map<String, Object>> references) {
        ObjectNode card = objectMapper.createObjectNode();

        // 卡片配置
        ObjectNode config = card.putObject("config");
        config.put("wide_screen_mode", true);

        // 卡片头部
        ObjectNode header = card.putObject("header");
        ObjectNode title = header.putObject("title");
        title.put("tag", "plain_text");
        title.put("content", "RAG 智能助手");
        header.put("template", "blue");

        // 卡片元素
        ArrayNode elements = card.putArray("elements");

        // 1. AI 回复内容（处理图片 URL 后放入 lark_md）
        String processedContent = processContentForLarkMd(aiContent);
        if (!processedContent.isBlank()) {
            ObjectNode contentDiv = elements.addObject();
            contentDiv.put("tag", "div");
            ObjectNode text = contentDiv.putObject("text");
            text.put("tag", "lark_md");
            text.put("content", processedContent);
        }

        // 2. 引用文档列表
        if (references != null && !references.isEmpty()) {
            // 分隔线
            ObjectNode hr = elements.addObject();
            hr.put("tag", "hr");

            // 引用标题
            ObjectNode refTitle = elements.addObject();
            refTitle.put("tag", "div");
            ObjectNode refTitleText = refTitle.putObject("text");
            refTitleText.put("tag", "lark_md");
            refTitleText.put("content", "**📚 引用文档（" + references.size() + "篇）**");

            // 引用列表
            StringBuilder refContent = new StringBuilder();
            for (int i = 0; i < references.size(); i++) {
                Map<String, Object> ref = references.get(i);
                String docTitle = String.valueOf(ref.getOrDefault("documentTitle", "未知文档"));
                String fileUrl = String.valueOf(ref.getOrDefault("fileUrl", "#"));
                double score = ((Number) ref.getOrDefault("score", 0.0)).doubleValue();
                int scorePct = (int) (score * 100);

                if (i > 0) refContent.append("\n");
                refContent.append(i + 1).append(". [")
                        .append(escapeLarkMd(docTitle))
                        .append("](")
                        .append(fileUrl)
                        .append(") · 相关度 ")
                        .append(scorePct)
                        .append("%");
            }

            ObjectNode refDiv = elements.addObject();
            refDiv.put("tag", "div");
            ObjectNode refText = refDiv.putObject("text");
            refText.put("tag", "lark_md");
            refText.put("content", refContent.toString());
        }

        // 3. 底部备注
        ObjectNode footer = elements.addObject();
        footer.put("tag", "note");
        ArrayNode noteElements = footer.putArray("elements");
        ObjectNode noteText = noteElements.addObject();
        noteText.put("tag", "lark_md");
        noteText.put("content", "基于知识库检索增强生成 · 回答仅供参考");

        try {
            return objectMapper.writeValueAsString(card);
        } catch (Exception e) {
            log.error("构建飞书卡片 JSON 失败", e);
            return "{}";
        }
    }

    /**
     * 构建简单文本回复卡片（非 RAG 场景，如通用闲聊或错误提示）
     *
     * @param text 回复文本
     * @return 飞书卡片 JSON 字符串
     */
    public String buildSimpleCard(String text) {
        return buildRagCard(text, null);
    }

    /**
     * 处理 AI 回复内容，适配飞书 lark_md 格式
     * <p>处理步骤：</p>
     * <ol>
     *   <li>将 MinIO 内部图片 URL 替换为外部可访问地址</li>
     *   <li>将代码块转换为 lark_md 兼容格式</li>
     *   <li>清理不支持的 Markdown 语法</li>
     * </ol>
     *
     * @param content 原始 Markdown 内容
     * @return 飞书 lark_md 兼容的内容
     */
    private String processContentForLarkMd(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }

        String result = content;

        // 1. 替换图片 URL：将内部 MinIO 地址替换为外部可访问地址
        result = replaceImageUrls(result);

        // 2. 处理代码块：飞书 lark_md 支持代码块，但语法略有不同
        result = CODE_BLOCK_PATTERN.matcher(result).replaceAll(match -> {
            String lang = match.group(1);
            String code = match.group(2).trim();
            // 飞书 lark_md 代码块格式
            return "```" + (lang.isEmpty() ? "" : lang) + "\n" + code + "\n```";
        });

        // 3. 飞书 lark_md 内容长度限制为 30000 字符，超出时截断
        if (result.length() > 28000) {
            result = result.substring(0, 28000) + "\n\n...(内容过长已截断)";
        }

        return result;
    }

    /**
     * 替换 Markdown 图片中的 MinIO 内部 URL 为外部可访问地址
     * <p>将 ![alt](http://localhost:9000/...) 替换为 ![alt](http://public-ip:9000/...)</p>
     *
     * @param content 包含图片的 Markdown 内容
     * @return 替换后的内容
     */
    private String replaceImageUrls(String content) {
        String externalBase = feishuProperties.getExternalImageBaseUrl();
        if (externalBase == null || externalBase.isBlank()) {
            return content;
        }

        // 获取内部 endpoint 用于替换（从配置中推断，通常为 http://localhost:9000）
        Matcher matcher = IMAGE_PATTERN.matcher(content);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String alt = matcher.group(1);
            String url = matcher.group(2);

            // 替换内部地址为外部地址
            // 匹配常见的内部地址模式：localhost、127.0.0.1、内网 IP 等
            url = url.replaceFirst("https?://localhost:\\d+", externalBase);
            url = url.replaceFirst("https?://127\\.0\\.0\\.1:\\d+", externalBase);
            url = url.replaceFirst("https?://host\\.docker\\.internal:\\d+", externalBase);

            matcher.appendReplacement(result, "![" + Matcher.quoteReplacement(alt) + "](" + Matcher.quoteReplacement(url) + ")");
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * 转义 lark_md 中的特殊字符（防止格式破坏）
     * <p>仅转义文档标题等用户输入内容，不转义 AI 回复主体</p>
     */
    private String escapeLarkMd(String text) {
        if (text == null) return "";
        // 飞书 lark_md 中需要转义的字符有限，主要防止括号和方括号破坏链接语法
        return text.replace("[", "\\[")
                .replace("]", "\\]")
                .replace("(", "\\(")
                .replace(")", "\\)");
    }
}
