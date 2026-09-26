package com.lujia.rag.raglangchain4j.chat.rag;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 引用文档上下文
 * <p>使用 ThreadLocal 在 RAG 管道组件之间传递引用文档信息，
 * 使检索器（ContentRetriever）能够将命中的文档引用信息传递给聊天服务</p>
 *
 * <p>引用信息格式：{@code [{documentId, documentTitle, fileUrl, score}, ...]}</p>
 */
public class ReferenceContext {

    private static final ThreadLocal<List<Map<String, Object>>> REFERENCES = new ThreadLocal<>();

    /**
     * 初始化当前线程的引用列表
     */
    public static void set(List<Map<String, Object>> references) {
        REFERENCES.set(references);
    }

    /**
     * 获取当前线程的引用列表
     */
    public static List<Map<String, Object>> get() {
        return REFERENCES.get();
    }

    /**
     * 清除当前线程的引用列表
     */
    public static void clear() {
        REFERENCES.remove();
    }

    /**
     * 添加一条引用文档记录（自动去重，同一 documentId 只保留最高分）
     *
     * @param documentId    文档ID
     * @param documentTitle 文档标题
     * @param fileUrl       文件URL
     * @param score         相似度分数
     */
    public static void addReference(String documentId, String documentTitle, String fileUrl, double score) {
        List<Map<String, Object>> refs = REFERENCES.get();
        if (refs == null) {
            return;
        }

        // 检查是否已存在相同文档，保留最高分
        for (Map<String, Object> ref : refs) {
            if (documentId.equals(ref.get("documentId"))) {
                double existingScore = ((Number) ref.getOrDefault("score", 0.0)).doubleValue();
                if (score > existingScore) {
                    ref.put("score", score);
                }
                return;
            }
        }

        // 新文档，添加引用
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("documentId", documentId);
        ref.put("documentTitle", documentTitle);
        ref.put("fileUrl", fileUrl);
        ref.put("score", score);
        refs.add(ref);
    }

    /**
     * 将引用列表序列化为 JSON 字符串
     *
     * @return JSON 数组字符串，如无引用则返回 "[]"
     */
    public static String getReferencesJson() {
        List<Map<String, Object>> refs = REFERENCES.get();
        if (refs == null || refs.isEmpty()) {
            return "[]";
        }

        // 按分数降序排序
        refs.sort((a, b) -> Double.compare(
                ((Number) b.getOrDefault("score", 0.0)).doubleValue(),
                ((Number) a.getOrDefault("score", 0.0)).doubleValue()
        ));

        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < refs.size(); i++) {
            Map<String, Object> ref = refs.get(i);
            if (i > 0) json.append(",");
            json.append("{");
            json.append("\"documentId\":").append(ref.get("documentId"));
            json.append(",\"documentTitle\":\"").append(JsonEscapeUtil.escapeJson(String.valueOf(ref.getOrDefault("documentTitle", "")))).append("\"");
            json.append(",\"fileUrl\":\"").append(JsonEscapeUtil.escapeJson(String.valueOf(ref.getOrDefault("fileUrl", "")))).append("\"");
            json.append(",\"score\":").append(String.format(Locale.ROOT, "%.2f", ((Number) ref.getOrDefault("score", 0.0)).doubleValue()));
            json.append("}");
        }
        json.append("]");
        return json.toString();
    }

    private ReferenceContext() {
    }
}
