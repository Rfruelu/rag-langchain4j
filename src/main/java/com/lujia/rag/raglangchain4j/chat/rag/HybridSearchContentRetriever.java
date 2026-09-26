package com.lujia.rag.raglangchain4j.chat.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lujia.rag.raglangchain4j.document.constant.DocumentConstant;
import com.lujia.rag.raglangchain4j.document.entity.KnowledgeDocumentSegment;
import com.lujia.rag.raglangchain4j.document.entity.RagKnowledgeDocument;
import com.lujia.rag.raglangchain4j.document.mapper.RagKnowledgeDocumentMapper;
import com.lujia.rag.raglangchain4j.document.service.KnowledgeDocumentSegmentService;
import com.lujia.rag.raglangchain4j.document.service.VectorStoreService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import dev.langchain4j.data.segment.TextSegment;

/**
 * 混合检索内容检索器
 * <p>封装 VectorStoreService 的混合检索能力（向量语义检索 + BM25 关键词检索 + RRF 融合排序），
 * 作为 langchain4j ContentRetriever 接入 RetrievalAugmentor RAG 流程</p>
 * <p>支持父子文档替换：检索命中子文档时，自动替换为父文档内容，并通过 Redis 缓存父文档</p>
 */
@Slf4j
public class HybridSearchContentRetriever implements ContentRetriever {

    /** Redis 缓存 key 前缀：父文档内容 */
    private static final String PARENT_CHUNK_CACHE_PREFIX = "rag:parent_chunk:";

    /** 父文档缓存过期时间（小时） */
    private static final Duration PARENT_CHUNK_CACHE_TTL = Duration.ofHours(24);

    private final VectorStoreService vectorStoreService;

    private final KnowledgeDocumentSegmentService segmentService;

    private final RagKnowledgeDocumentMapper documentMapper;

    private final RedissonClient redissonClient;

    private final int maxResults;

    private final double minScore;

    public HybridSearchContentRetriever(VectorStoreService vectorStoreService,
                                        KnowledgeDocumentSegmentService segmentService,
                                        RagKnowledgeDocumentMapper documentMapper,
                                        RedissonClient redissonClient,
                                        int maxResults, double minScore) {
        this.vectorStoreService = vectorStoreService;
        this.segmentService = segmentService;
        this.documentMapper = documentMapper;
        this.redissonClient = redissonClient;
        this.maxResults = maxResults;
        this.minScore = minScore;
    }

    @Override
    public List<Content> retrieve(Query query) {
        try {
            log.info("混合检索开始: query='{}'", query.text());

            // 发射 RAG 管道状态：步骤3 - 知识检索
            RagStatusContext.emitStatus(3, 4, "正在检索知识库", "向量语义检索 + BM25 关键词检索 + 重排序");

            // 从 IntentContextHolder 获取当前用户类型，用于权限过滤
            String userType = IntentContextHolder.getUserType();

            List<VectorStoreService.SearchResult> results = vectorStoreService.vectorSearch(
                    query.text(), maxResults, minScore, userType);

            // 父子文档替换：命中子文档时替换为父文档内容
            results = resolveParentChunks(results);

            // 收集引用文档信息并写入 ReferenceContext
            collectReferences(results);

            // 构建 Content 时保留元数据，便于下游组件获取文档引用信息
            List<Content> contents = results.stream()
                    .map(r -> {
                        Map<String, Object> meta = r.getMetadata();
                        if (meta != null && !meta.isEmpty()) {
                            TextSegment ts = TextSegment.from(r.getText(),
                                    dev.langchain4j.data.document.Metadata.from(
                                            meta.entrySet().stream()
                                                    .collect(Collectors.toMap(
                                                            Map.Entry::getKey,
                                                            e -> String.valueOf(e.getValue())))));
                            return Content.from(ts);
                        }
                        return Content.from(r.getText());
                    })
                    .toList();

            log.info("混合检索完成: query='{}', 检索到 {} 条结果", query.text(), contents.size());
            return contents;

        } catch (Exception e) {
            log.error("混合检索异常: query='{}'", query.text(), e);
            return List.of();
        }
    }

    /**
     * 收集检索结果中的引用文档信息，写入 ReferenceContext
     * <p>从检索结果的 metadata 中提取 documentId，批量查询文档标题和文件链接，
     * 并写入 ReferenceContext 供聊天服务读取</p>
     *
     * @param results 检索结果列表
     */
    private void collectReferences(List<VectorStoreService.SearchResult> results) {
        // 收集所有唯一的 documentId
        Map<String, Double> docIdToMaxScore = new LinkedHashMap<>();
        for (VectorStoreService.SearchResult result : results) {
            Map<String, Object> meta = result.getMetadata();
            if (meta == null) continue;
            String docId = String.valueOf(meta.getOrDefault("documentId", ""));
            if (docId.isEmpty() || "null".equals(docId)) continue;
            double score = result.getScore();
            docIdToMaxScore.merge(docId, score, Math::max);
        }

        if (docIdToMaxScore.isEmpty()) {
            return;
        }

        // 批量查询文档信息
        for (String docId : docIdToMaxScore.keySet()) {
            try {
                RagKnowledgeDocument doc = documentMapper.selectById(Long.valueOf(docId));
                if (doc != null) {
                    double score = docIdToMaxScore.get(docId);
                    ReferenceContext.addReference(docId, doc.getTitle(), doc.getFileUrl(), score);
                }
            } catch (Exception e) {
                log.warn("查询引用文档信息失败: documentId={}", docId, e);
            }
        }
    }

    /**
     * 父子文档替换处理
     * <p>处理逻辑：</p>
     * <ol>
     *   <li>遍历检索结果，检查 metadata 中是否存在 parentChunkId</li>
     *   <li>有 parentChunkId 的为子文档，收集其 parentChunkId</li>
     *   <li>查询父文档内容（优先 Redis 缓存，缓存未命中则查 DB）</li>
     *   <li>移除所有子文档及其兄弟文档，用父文档内容替代</li>
     * </ol>
     *
     * @param results 原始检索结果
     * @return 替换后的检索结果
     */
    private List<VectorStoreService.SearchResult> resolveParentChunks(
            List<VectorStoreService.SearchResult> results) {

        // 1. 收集所有子文档的 parentChunkId 和对应的子文档 chunkId
        Map<String, String> childToParentMap = new LinkedHashMap<>();
        Set<String> parentChunkIds = new LinkedHashSet<>();

        for (VectorStoreService.SearchResult result : results) {
            Map<String, Object> metadata = result.getMetadata();
            if (metadata != null && metadata.containsKey(DocumentConstant.PARENT_CHUNK_ID)) {
                String parentChunkId = String.valueOf(metadata.get(DocumentConstant.PARENT_CHUNK_ID));
                String childChunkId = String.valueOf(metadata.get(DocumentConstant.CHUNK_ID));
                childToParentMap.put(childChunkId, parentChunkId);
                parentChunkIds.add(parentChunkId);
            }
        }

        if (childToParentMap.isEmpty()) {
            return results;
        }

        log.info("检测到 {} 个子文档命中，涉及 {} 个父文档，开始父子文档替换",
                childToParentMap.size(), parentChunkIds.size());

        // 2. 批量查询父文档内容（优先 Redis 缓存）
        Map<String, String> parentContentMap = batchLoadParentContents(parentChunkIds);

        // 3. 收集需要移除的 chunkId：所有子文档 + 结果中属于同一兄弟组的其他子文档
        Set<String> chunkIdsToRemove = new HashSet<>(childToParentMap.keySet());

        // 查找结果中是否有与命中的子文档同组的兄弟文档
        for (VectorStoreService.SearchResult result : results) {
            Map<String, Object> metadata = result.getMetadata();
            if (metadata == null) continue;
            String chunkId = String.valueOf(metadata.get(DocumentConstant.CHUNK_ID));
            // 如果该结果的 parentChunkId 在已收集的父文档集合中，说明它也是某个命中子文档的兄弟
            if (metadata.containsKey(DocumentConstant.PARENT_CHUNK_ID)) {
                String parentId = String.valueOf(metadata.get(DocumentConstant.PARENT_CHUNK_ID));
                if (parentChunkIds.contains(parentId) && !chunkIdsToRemove.contains(chunkId)) {
                    chunkIdsToRemove.add(chunkId);
                    log.debug("移除兄弟文档: chunkId={}, parentChunkId={}", chunkId, parentId);
                }
            }
        }

        // 4. 过滤结果：移除子文档和兄弟文档，添加父文档
        List<VectorStoreService.SearchResult> finalResults = new ArrayList<>();
        Set<String> addedParentIds = new HashSet<>();

        for (VectorStoreService.SearchResult result : results) {
            Map<String, Object> metadata = result.getMetadata();
            String chunkId = metadata != null ? String.valueOf(metadata.get(DocumentConstant.CHUNK_ID)) : null;

            if (chunkIdsToRemove.contains(chunkId)) {
                // 子文档或兄弟文档：跳过，用父文档替代
                continue;
            }

            // 非子文档的普通结果，直接保留
            finalResults.add(result);
        }

        // 添加父文档内容（按原始子文档的顺序，取最高分的子文档对应的父文档）
        for (Map.Entry<String, String> entry : childToParentMap.entrySet()) {
            String parentChunkId = entry.getValue();
            if (addedParentIds.contains(parentChunkId)) {
                continue;
            }
            String parentContent = parentContentMap.get(parentChunkId);
            if (parentContent != null && !parentContent.isBlank()) {
                VectorStoreService.SearchResult parentResult = new VectorStoreService.SearchResult();
                parentResult.setText(parentContent);
                parentResult.setScore(1.0);
                Map<String, Object> parentMeta = new HashMap<>();
                parentMeta.put(DocumentConstant.CHUNK_ID, parentChunkId);
                parentResult.setMetadata(parentMeta);
                finalResults.add(parentResult);
                addedParentIds.add(parentChunkId);
                log.debug("添加父文档: parentChunkId={}", parentChunkId);
            } else {
                log.warn("父文档内容为空: parentChunkId={}", parentChunkId);
            }
        }

        log.info("父子文档替换完成: 移除 {} 个子/兄弟文档, 添加 {} 个父文档, 最终 {} 条结果",
                chunkIdsToRemove.size(), addedParentIds.size(), finalResults.size());

        return finalResults;
    }

    /**
     * 批量加载父文档内容
     * <p>优先从 Redis 缓存读取，缓存未命中的从 DB 查询并回填缓存</p>
     *
     * @param parentChunkIds 父文档 chunkId 集合
     * @return parentChunkId → 父文档内容 的映射
     */
    private Map<String, String> batchLoadParentContents(Set<String> parentChunkIds) {
        Map<String, String> contentMap = new HashMap<>();
        List<String> missedChunkIds = new ArrayList<>();

        // 1. 批量查询 Redis 缓存
        for (String parentChunkId : parentChunkIds) {
            try {
                RBucket<String> bucket = redissonClient.getBucket(PARENT_CHUNK_CACHE_PREFIX + parentChunkId);
                String cached = bucket.get();
                if (cached != null) {
                    contentMap.put(parentChunkId, cached);
                    log.debug("父文档缓存命中: parentChunkId={}", parentChunkId);
                } else {
                    missedChunkIds.add(parentChunkId);
                }
            } catch (Exception e) {
                log.warn("Redis 缓存读取失败: parentChunkId={}", parentChunkId, e);
                missedChunkIds.add(parentChunkId);
            }
        }

        // 2. 缓存未命中的从 DB 查询
        if (!missedChunkIds.isEmpty()) {
            log.info("父文档缓存未命中 {} 个，从 DB 查询: {}", missedChunkIds.size(), missedChunkIds);

            LambdaQueryWrapper<KnowledgeDocumentSegment> wrapper = new LambdaQueryWrapper<>();
            wrapper.in(KnowledgeDocumentSegment::getChunkId, missedChunkIds);
            List<KnowledgeDocumentSegment> parentSegments = segmentService.list(wrapper);

            for (KnowledgeDocumentSegment parent : parentSegments) {
                contentMap.put(parent.getChunkId(), parent.getText());

                // 回填 Redis 缓存
                try {
                    RBucket<String> bucket = redissonClient.getBucket(PARENT_CHUNK_CACHE_PREFIX + parent.getChunkId());
                    bucket.set(parent.getText(), PARENT_CHUNK_CACHE_TTL);
                    log.debug("父文档缓存回填: parentChunkId={}", parent.getChunkId());
                } catch (Exception e) {
                    log.warn("Redis 缓存写入失败: parentChunkId={}", parent.getChunkId(), e);
                }
            }
        }

        return contentMap;
    }
}
