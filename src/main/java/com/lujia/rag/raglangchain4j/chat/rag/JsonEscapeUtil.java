package com.lujia.rag.raglangchain4j.chat.rag;

/**
 * JSON 字符串转义工具
 * <p>供拼接 JSON 的组件（{@link RagStatusContext}、{@link ReferenceContext} 等）统一转义文本内容</p>
 */
public final class JsonEscapeUtil {

    /**
     * 简单转义 JSON 字符串中的特殊字符
     *
     * @param text 原始文本
     * @return 转义后的文本，null 返回空字符串
     */
    public static String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private JsonEscapeUtil() {
    }
}
