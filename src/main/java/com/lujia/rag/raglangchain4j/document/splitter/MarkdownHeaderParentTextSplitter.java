package com.lujia.rag.raglangchain4j.document.splitter;


import cn.hutool.core.util.IdUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.lujia.rag.raglangchain4j.document.constant.DocumentConstant.*;


/**
 * Markdown文档分割器（父子分片模式），基于标题层级进行文档分段
 * 支持保留元数据等高级特性
 *
 * @author andyflury （https://github.com/langchain4j/langchain4j/issues/574 ）
 */
public class MarkdownHeaderParentTextSplitter extends AbstractMarkdownHeaderTextSplitter {

    /**
     * 构造函数
     *
     * @param headersToSplitOn 标题分割映射表，key为标题标记（如"#"、"##"），value为元数据中的键名
     * @param returnEachLine   是否按行返回结果，false时会聚合相同元数据的行
     * @param stripHeaders     是否在结果中移除标题行
     */
    public MarkdownHeaderParentTextSplitter(Map<String, String> headersToSplitOn, boolean returnEachLine, boolean stripHeaders) {
        this(headersToSplitOn, returnEachLine, stripHeaders, 0, 0);

    }

    public MarkdownHeaderParentTextSplitter(int chunkSize, int overlap) {
        super(DEFAULT_HEADERS_TO_SPLIT, true, false, chunkSize, overlap);
    }

    /**
     * 构造函数（通过标题级别指定分割层级）
     *
     * @param titleLevel     标题级别（1-6），表示按1到titleLevel级标题进行分割
     * @param returnEachLine 是否按行返回结果，false时会聚合相同元数据的行
     * @param stripHeaders   是否在结果中移除标题行
     * @param chunkSize      每个分片的最大字符数，超出则按chunkSize再次切割，0表示不限制
     * @param overlap        相邻分片之间的重叠字符数
     */
    public MarkdownHeaderParentTextSplitter(int titleLevel, boolean returnEachLine, boolean stripHeaders, int chunkSize, int overlap) {
        this(buildHeadersMap(titleLevel), returnEachLine, stripHeaders, chunkSize, overlap);
    }

    /**
     * 根据标题级别生成标题分割映射表
     *
     * @param titleLevel 标题级别（1-6）
     * @return 标题分割映射表
     */
    private static Map<String, String> buildHeadersMap(int titleLevel) {
        if (titleLevel < 1 || titleLevel > 6) {
            throw new IllegalArgumentException("titleLevel must be between 1 and 6, but got: " + titleLevel);
        }
        String[] names = {"title", "subtitle", "subsubtitle", "subsubsubtitle", "subsubsubsubtitle", "subsubsubsubsubtitle"};
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i <= titleLevel; i++) {
            String key = "#".repeat(i);
            headers.put(key, names[i - 1]);
        }
        return headers;
    }

    /**
     * 构造函数（支持 chunkSize 和 overlap）
     *
     * @param headersToSplitOn 标题分割映射表，key为标题标记（如"#"、"##"），value为元数据中的键名
     * @param returnEachLine   是否按行返回结果，false时会聚合相同元数据的行
     * @param stripHeaders     是否在结果中移除标题行
     * @param chunkSize        每个分片的最大字符数，超出则按chunkSize再次切割，0表示不限制
     * @param overlap          相邻分片之间的重叠字符数
     */
    public MarkdownHeaderParentTextSplitter(Map<String, String> headersToSplitOn, boolean returnEachLine, boolean stripHeaders, int chunkSize, int overlap) {
        super(headersToSplitOn, returnEachLine, stripHeaders, chunkSize, overlap);
    }


    /**
     * 对超出 chunkSize 的分片进行二次切割
     * <p>
     * 切割规则：
     * - 未超出 chunkSize 的分片保持不变
     * - 超出 chunkSize 的分片：保留完整分片（标记为跳过embedding），同时生成拆分后的多个分片
     *
     * @param segments 原始分片列表
     * @return 切割后的分片列表
     */
    @Override
    protected List<DocumentWithMetadata> splitByChunkSize(List<DocumentWithMetadata> segments) {
        List<DocumentWithMetadata> result = new ArrayList<>();
        for (DocumentWithMetadata segment : segments) {
            String content = segment.getContent();
            if (content.length() <= chunkSize) {
                // 未超出 chunkSize，保持原分片不变
                result.add(segment);
            } else {
                // 超出 chunkSize，需要二次切割
                // 1. 首先保留完整分片，标记为跳过embedding
                Map<String, Object> fullMetadata = new HashMap<>(segment.getMetadata());

                String parentChunkId = IdUtil.getSnowflakeNextIdStr();

                fullMetadata.put(CHUNK_ID, parentChunkId);
                fullMetadata.put(SKIP_EMBEDDING, 1);
                result.add(new DocumentWithMetadata(content, fullMetadata));

                // 2. 生成拆分后的多个分片
                int start = 0;
                while (start < content.length()) {
                    int end = Math.min(start + chunkSize, content.length());
                    String subContent = content.substring(start, end);

                    // 复制元数据并进行更新
                    Map<String, Object> subMetadata = new HashMap<>(segment.getMetadata());
                    subMetadata.put(CHUNK_ID, IdUtil.getSnowflakeNextIdStr());
                    subMetadata.put(PARENT_CHUNK_ID, parentChunkId);

                    result.add(new DocumentWithMetadata(subContent, subMetadata));

                    if (end == content.length()) {
                        break;
                    }
                    // 下一片的起始位置 = 当前片的结束位置 - overlap
                    start = end - Math.min(overlap, end);
                }
            }
        }
        return result;
    }
}
