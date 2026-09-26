package com.lujia.rag.raglangchain4j.document.splitter;


import cn.hutool.core.util.IdUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.lujia.rag.raglangchain4j.document.constant.DocumentConstant.*;


/**
 * Markdown文档分割器（兄弟分片模式），基于标题层级进行文档分段
 * 支持保留元数据、父子分段关系等高级特性
 *
 * @author andyflury （https://github.com/langchain4j/langchain4j/issues/574 ）
 */
@Slf4j
public class MarkdownHeaderBrotherTextSplitter extends AbstractMarkdownHeaderTextSplitter {

    /**
     * 是否启用父子分段模式
     */
    private final boolean parentChildModel;

    /**
     * 构造函数
     *
     * @param headersToSplitOn 标题分割映射表，key为标题标记（如"#"、"##"），value为元数据中的键名
     * @param returnEachLine   是否按行返回结果，false时会聚合相同元数据的行
     * @param stripHeaders     是否在结果中移除标题行
     * @param parentChildModel 是否启用父子分段模式，启用后会在元数据中添加parentChunkId
     */
    public MarkdownHeaderBrotherTextSplitter(Map<String, String> headersToSplitOn, boolean returnEachLine, boolean stripHeaders, boolean parentChildModel) {
        this(headersToSplitOn, returnEachLine, stripHeaders, parentChildModel, 0, 0);

    }

    public MarkdownHeaderBrotherTextSplitter(int chunkSize, int overlap) {
        this(DEFAULT_HEADERS_TO_SPLIT, true, false, true, chunkSize, overlap);
    }

    /**
     * 构造函数（支持 chunkSize 和 overlap）
     *
     * @param headersToSplitOn 标题分割映射表，key为标题标记（如"#"、"##"），value为元数据中的键名
     * @param returnEachLine   是否按行返回结果，false时会聚合相同元数据的行
     * @param stripHeaders     是否在结果中移除标题行
     * @param parentChildModel 是否启用父子分段模式，启用后会在元数据中添加parentChunkId
     * @param chunkSize        每个分片的最大字符数，超出则按chunkSize再次切割，0表示不限制
     * @param overlap          相邻分片之间的重叠字符数
     */
    public MarkdownHeaderBrotherTextSplitter(Map<String, String> headersToSplitOn, boolean returnEachLine, boolean stripHeaders, boolean parentChildModel, int chunkSize, int overlap) {
        super(headersToSplitOn, returnEachLine, stripHeaders, chunkSize, overlap);
        this.parentChildModel = parentChildModel;
    }

    /**
     * 处理父子分段关系
     * 遍历所有分块，为非顶级标题建立父子关系
     */
    @Override
    protected void enrichAggregatedChunks(List<Line> aggregatedChunks) {
        if (!parentChildModel) {
            return;
        }
        try {
            for (int i = 0; i < aggregatedChunks.size(); i++) {
                Map<String, Object> currentMetaData = aggregatedChunks.get(i).getMetadata();
                Integer headerLevel = (Integer) currentMetaData.get(HEADER_LEVEL);
                // 顶级标题（level=1）或无标题的分块跳过
                if (headerLevel == null || headerLevel == 1) {
                    continue;
                }

                // 向前查找第一个级别更低的标题作为父节点
                if (headerLevel > 1) {
                    for (int j = i - 1; j >= 0; j--) {
                        Map<String, Object> lastMetaData = aggregatedChunks.get(j).getMetadata();
                        Integer lastHeaderLevel = (Integer) lastMetaData.get(HEADER_LEVEL);
                        if (lastHeaderLevel != null && lastHeaderLevel < headerLevel) {
                            // 将父节点的chunkId设置为当前节点的parentChunkId
                            currentMetaData.put(PARENT_CHUNK_ID, lastMetaData.get(CHUNK_ID));
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("父子模式转换失败，{}", e.getMessage(), e);
        }
    }

    /**
     * 对超出 chunkSize 的分片进行二次切割
     * <p>
     * 切割规则：
     * - 未超出 chunkSize 的分片保持不变
     * - 超出 chunkSize 的分片按字符数切割，相邻分片之间保留 overlap 个字符的重叠
     * - 切割出的同组分片之间共享同一个 brotherChunkId，方便检索时拼接
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
                // 生成共同的 brotherChunkId，赋予同组所有分片
                String brotherChunkId = IdUtil.getSnowflakeNextIdStr();
                List<DocumentWithMetadata> subChunks = new ArrayList<>();

                int start = 0;
                while (start < content.length()) {
                    int end = Math.min(start + chunkSize, content.length());
                    String subContent = content.substring(start, end);

                    // 复制元数据并进行更新
                    Map<String, Object> subMetadata = new HashMap<>(segment.getMetadata());
                    subMetadata.put(CHUNK_ID, IdUtil.getSnowflakeNextIdStr());
                    subMetadata.put(BROTHER_CHUNK_ID, brotherChunkId);

                    subChunks.add(new DocumentWithMetadata(subContent, subMetadata));

                    if (end == content.length()) {
                        break;
                    }
                    // 下一片的起始位置 = 当前片的结束位置 - overlap
                    start = end - Math.min(overlap, end);
                }

                // 回填 brotherChunkIndex 和 brotherChunkTotal，方便后续按序拼接
                int total = subChunks.size();
                for (int i = 0; i < total; i++) {
                    subChunks.get(i).getMetadata().put(BROTHER_CHUNK_INDEX, i + 1);
                    subChunks.get(i).getMetadata().put(BROTHER_CHUNK_TOTAL, total);
                }

                result.addAll(subChunks);
            }
        }
        return result;
    }
}
