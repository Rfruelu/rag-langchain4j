package com.lujia.rag.raglangchain4j.document.dto;

/**
 * 文档分割参数
 * <p>用于配置文档分割策略及相关参数，配合 {@link com.lujia.rag.raglangchain4j.document.splitter.DocumentSplitterFactory} 使用</p>
 *
 * @param splitType  分割类型，对应 {@link com.lujia.rag.raglangchain4j.document.enums.SplitType} 中的枚举值：
 *                   LENGTH（按长度）、TITLE（按标题）、REGEX（按正则）、SMART（智能）、SEPARATOR（按分隔符）
 * @param chunkSize  每个分块的最大字符数，适用于所有分割类型
 * @param overlap    相邻分块之间的重叠字符数，用于保留上下文连贯性
 * @param titleLevel 标题级别（1-6），仅在 splitType 为 TITLE 时生效，表示按几级标题进行分割
 * @param separator  自定义分隔符，仅在 splitType 为 SEPARATOR 时生效
 * @param regex      自定义正则表达式，仅在 splitType 为 REGEX 时生效
 */
public record DocumentSplitParam(String splitType,
                                 Integer chunkSize,
                                 Integer overlap,
                                 Integer titleLevel,
                                 String separator,
                                 String regex) {
}
