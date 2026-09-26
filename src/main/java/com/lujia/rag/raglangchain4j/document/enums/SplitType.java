package com.lujia.rag.raglangchain4j.document.enums;

/**
 * 文档分割类型枚举
 * <p>定义文档分割器支持的切分策略，配合 {@link com.lujia.rag.raglangchain4j.document.dto.DocumentSplitParam} 使用</p>
 *
 * @see com.lujia.rag.raglangchain4j.document.splitter.DocumentSplitterFactory
 */
public enum SplitType {

    /**
     * 按长度切分
     * <p>基于单词边界按字符数分割，避免截断单词。使用 DocumentByWordSplitter</p>
     */
    LENGTH,

    /**
     * 按标题切分
     * <p>基于 Markdown 标题层级分割，保留父子分段关系。使用 MarkdownHeaderParentTextSplitter</p>
     */
    TITLE,

    /**
     * 按正则切分
     * <p>使用自定义正则表达式作为分割点，二次分割使用双换行符。使用 DocumentByRegexSplitter</p>
     */
    REGEX,

    /**
     * 智能切分
     * <p>自动识别 Markdown 标题结构进行分割，overlap 默认为 chunkSize 的 10%。使用 MarkdownHeaderParentTextSplitter</p>
     */
    SMART,

    /**
     * 按分隔符切分
     * <p>使用自定义分隔符进行分割，二次分割使用双换行符。使用 DocumentByRegexSplitter</p>
     */
    SEPARATOR;
}
