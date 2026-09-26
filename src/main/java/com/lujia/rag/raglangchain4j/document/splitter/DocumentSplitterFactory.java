package com.lujia.rag.raglangchain4j.document.splitter;

import com.lujia.rag.raglangchain4j.document.dto.DocumentSplitParam;
import com.lujia.rag.raglangchain4j.document.enums.SplitType;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentByRegexSplitter;
import dev.langchain4j.data.document.splitter.DocumentByWordSplitter;


/**
 * 文档分割器工厂类
 * <p>
 * 根据 {@link DocumentSplitParam} 中指定的分割类型（splitType），
 * 创建对应的 {@link DocumentSplitter} 实例。
 * </p>
 * <p>支持的分割类型：</p>
 * <ul>
 *   <li>{@link SplitType#TITLE} — 按 Markdown 标题层级切分，保留父子分段关系</li>
 *   <li>{@link SplitType#LENGTH} — 按字符长度切分，基于单词边界</li>
 *   <li>{@link SplitType#SEPARATOR} — 按自定义分隔符切分</li>
 *   <li>{@link SplitType#REGEX} — 按正则表达式切分</li>
 *   <li>{@link SplitType#SMART} — 智能切分，自动识别 Markdown 标题结构，overlap 默认为 chunkSize 的 10%</li>
 * </ul>
 *
 * @see DocumentSplitParam
 * @see SplitType
 */
public class DocumentSplitterFactory {

    /**
     * 根据分割参数创建对应的文档分割器
     *
     * @param documentSplitParam 分割参数，包含分割类型及相关配置
     * @return 对应的 DocumentSplitter 实例；若 splitType 无法匹配则返回 null
     */
    public static DocumentSplitter getInstance(DocumentSplitParam documentSplitParam) {
        // 按标题切分：使用 MarkdownHeaderParentTextSplitter，按指定标题级别分割，保留父子关系
        if (SplitType.TITLE.name().equals(documentSplitParam.splitType())) {
            return new MarkdownHeaderParentTextSplitter(documentSplitParam.titleLevel(), false, false, documentSplitParam.chunkSize(), documentSplitParam.overlap());
        }

        // 按长度切分：使用 DocumentByWordSplitter，按单词边界分割，避免截断单词
        if (SplitType.LENGTH.name().equals(documentSplitParam.splitType())) {
            return new DocumentByWordSplitter(documentSplitParam.chunkSize(), documentSplitParam.overlap());
        }

        // 按分隔符切分：使用指定的分隔符进行正则分割，默认以双换行符作为二次分割
        if (SplitType.SEPARATOR.name().equals(documentSplitParam.splitType())) {
            return new DocumentByRegexSplitter(documentSplitParam.separator(), "\\n\\n", documentSplitParam.chunkSize(), documentSplitParam.overlap());
        }

        // 按正则切分：使用自定义正则表达式进行分割，默认以双换行符作为二次分割
        if (SplitType.REGEX.name().equals(documentSplitParam.splitType())) {
            return new DocumentByRegexSplitter(documentSplitParam.regex(), "\\n\\n", documentSplitParam.chunkSize(), documentSplitParam.overlap());
        }

        // 智能切分：自动识别 Markdown 标题结构，overlap 取 chunkSize 的 10%
        if (SplitType.SMART.name().equals(documentSplitParam.splitType())) {
            return new MarkdownHeaderParentTextSplitter(documentSplitParam.chunkSize(), (int) (documentSplitParam.chunkSize() * 0.1));
        }

        return null;
    }
}
