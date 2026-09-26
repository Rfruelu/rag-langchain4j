package com.lujia.rag.raglangchain4j.document.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lujia.rag.raglangchain4j.document.entity.KnowledgeDocumentSegment;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.lujia.rag.raglangchain4j.document.entity.RagKnowledgeDocument;
import com.lujia.rag.raglangchain4j.document.lock.DistributeLock;
import com.lujia.rag.raglangchain4j.document.mapper.RagKnowledgeDocumentMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicHeader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.lujia.rag.raglangchain4j.document.constant.DocumentConstant;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 向量化存储服务
 * <p>负责将文档分片进行向量化并存储到 Elasticsearch</p>
 * <p>使用百炼 text-embedding 模型生成向量，通过 langchain4j ElasticsearchEmbeddingStore 存储</p>
 */
@Slf4j
@Service
public class VectorStoreService {

    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    /** 权限过滤下推到应用层时，两路检索各超量取回的倍数，用于补偿无权结果占用的 top-N 名额 */
    private static final int PERMISSION_OVERFETCH = 3;

    @Value("${embedding.api-key}")
    private String embeddingApiKey;

    @Value("${embedding.base-url}")
    private String embeddingBaseUrl;

    @Value("${embedding.model:text-embedding-v3}")
    private String embeddingModel;

    @Value("${elasticsearch.url:http://localhost:9200}")
    private String esUrl;

    @Value("${elasticsearch.username:}")
    private String esUsername;

    @Value("${elasticsearch.password:}")
    private String esPassword;

    @Value("${elasticsearch.index-name:rag-documents}")
    private String esIndexName;

    @Autowired
    private KnowledgeDocumentSegmentService segmentService;

    @Autowired
    private RagKnowledgeDocumentMapper documentMapper;

    /**
     * 外部注入的 EmbeddingModel（可选，用于测试环境替换为本地假模型）。
     * 未注入时使用百炼 text-embedding 模型
     */
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<EmbeddingModel> embeddingModelProvider;

    /** Embedding 模型实例 */
    private EmbeddingModel embeddingModelInstance;

    /** Elasticsearch 向量存储实例 */
    private ElasticsearchEmbeddingStore embeddingStore;

    /** Elasticsearch 客户端实例（用于自定义查询） */
    private ElasticsearchClient esClient;

    /** Elasticsearch 传输层实例（用于关闭底层连接） */
    private Rest5ClientTransport esTransport;

    /**
     * 初始化 Embedding 模型和 Elasticsearch 客户端
     * <p>服务为单例，在启动后一次性构建，避免懒加载的线程安全问题</p>
     */
    @PostConstruct
    void init() throws URISyntaxException {
        // Embedding 模型：优先使用外部注入的实现（测试用假模型），否则创建百炼 OpenAI 兼容客户端
        EmbeddingModel provided = embeddingModelProvider.getIfAvailable();
        if (provided != null) {
            embeddingModelInstance = provided;
            log.info("使用外部注入的 EmbeddingModel: {}", provided.getClass().getSimpleName());
        } else {
            embeddingModelInstance = OpenAiEmbeddingModel.builder()
                    .apiKey(embeddingApiKey)
                    .baseUrl(embeddingBaseUrl)
                    .modelName(embeddingModel)
                    .timeout(Duration.ofSeconds(60))
                    .build();
            log.info("Embedding 模型初始化完成: model={}, baseUrl={}", embeddingModel, embeddingBaseUrl);
        }

        // 构建底层 RestClient
        Rest5ClientBuilder builder = Rest5Client.builder(HttpHost.create(esUrl));

        // 如果配置了认证信息，添加认证头
        if (esUsername != null && !esUsername.isEmpty() && esPassword != null && !esPassword.isEmpty()) {
            String auth = esUsername + ":" + esPassword;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            builder.setDefaultHeaders(new Header[]{
                    new BasicHeader("Authorization", "Basic " + encodedAuth)
            });
        }

        Rest5Client rest5Client = builder.build();

        // 构建新版 ElasticsearchClient（elasticsearch-java 9.x 使用 Rest5ClientTransport）
        esTransport = new Rest5ClientTransport(rest5Client, new JacksonJsonpMapper());
        esClient = new ElasticsearchClient(esTransport);

        embeddingStore = ElasticsearchEmbeddingStore.builder()
                .client(esClient)
                .indexName(esIndexName)
                .build();

        log.info("Elasticsearch 向量存储初始化完成: url={}, index={}", esUrl, esIndexName);
    }

    /**
     * 关闭 Elasticsearch 客户端，释放连接资源
     */
    @PreDestroy
    void destroy() {
        if (esTransport != null) {
            try {
                esTransport.close();
            } catch (Exception e) {
                log.warn("关闭 ES 客户端失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 对指定文档的分片进行向量化并存储到 Elasticsearch
     * <p>处理流程：</p>
     * <ol>
     *   <li>查询文档下所有 STORED 状态的分片</li>
     *   <li>批量调用 Embedding 模型生成向量</li>
     *   <li>将向量和文本存储到 Elasticsearch</li>
     *   <li>更新分片状态为 VECTOR_STORED</li>
     * </ol>
     *
     * @param documentId 文档ID
     * @return 处理的向量数量
     */
    @DistributeLock(scene = "vectorizeAndStore", key = "#documentId")
    public int vectorizeAndStore(Long documentId) throws URISyntaxException {
        log.info("开始向量化存储文档分片: documentId={}", documentId);

        // 1. 查询需要向量化的分片
        LambdaQueryWrapper<KnowledgeDocumentSegment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocumentSegment::getDocumentId, documentId)
                .eq(KnowledgeDocumentSegment::getStatus, "STORED");
        List<KnowledgeDocumentSegment> segments = segmentService.list(wrapper);

        if (segments.isEmpty()) {
            log.info("文档 {} 没有需要向量化的分片", documentId);
            return 0;
        }

        log.info("文档 {} 共 {} 个分片需要向量化", documentId, segments.size());

        // 查询文档信息，用于在 ES 元数据中保存文档标题、文件链接和访问权限
        RagKnowledgeDocument document = documentMapper.selectById(documentId);
        String documentTitle = document != null ? document.getTitle() : null;
        String docFileUrl = document != null ? document.getFileUrl() : null;
        String docAccessibleBy = document != null ? document.getAccessibleBy() : null;

        // 2. 过滤掉跳过向量化的分片
        List<KnowledgeDocumentSegment> toEmbed = segments.stream()
                .filter(s -> s.getSkipEmbedding() == null || s.getSkipEmbedding() == 0)
                .toList();

        List<KnowledgeDocumentSegment> skipEmbed = segments.stream()
                .filter(s -> s.getSkipEmbedding() != null && s.getSkipEmbedding() == 1)
                .toList();

        // 3. 批量生成向量
        List<TextSegment> textSegments = new ArrayList<>();

        for (KnowledgeDocumentSegment segment : toEmbed) {
            // 构建 metadata，包含 chunkId、documentId、chunkOrder，以及 parentChunkId（如果存在）
            Map<String, String> metaMap = new HashMap<>();
            metaMap.put("chunkId", segment.getChunkId());
            metaMap.put("documentId", String.valueOf(segment.getDocumentId()));
            metaMap.put("chunkOrder", String.valueOf(segment.getChunkOrder()));

            // 添加文档标题、文件链接和访问权限到 ES 元数据
            if (documentTitle != null) {
                metaMap.put(DocumentConstant.DOCUMENT_TITLE, documentTitle);
            }
            if (docFileUrl != null) {
                metaMap.put(DocumentConstant.DOC_FILE_URL, docFileUrl);
            }

            // 写入文档访问权限到 ES metadata
            if (docAccessibleBy != null) {
                metaMap.put(DocumentConstant.ACCESSIBLE_BY, docAccessibleBy);
            }

            // 从 DB metadata JSON 中提取 parentChunkId，用于检索时父子文档替换
            if (segment.getMetadata() != null && !segment.getMetadata().isBlank()) {
                try {
                    Map<String, Object> dbMeta = OBJECT_MAPPER.readValue(segment.getMetadata(), Map.class);
                    Object parentChunkId = dbMeta.get(DocumentConstant.PARENT_CHUNK_ID);
                    if (parentChunkId != null) {
                        metaMap.put(DocumentConstant.PARENT_CHUNK_ID, parentChunkId.toString());
                    }
                } catch (Exception e) {
                    log.warn("解析分片 metadata 失败: chunkId={}, error={}", segment.getChunkId(), e.getMessage());
                }
            }

            TextSegment textSegment = TextSegment.from(
                    segment.getText(),
                    dev.langchain4j.data.document.Metadata.from(metaMap)
            );
            textSegments.add(textSegment);
        }

        if (!textSegments.isEmpty()) {
            // 分批调用 embedding API（百炼 text-embedding-v3 限制每次最多 10 条）
            int batchSize = 10;
            EmbeddingModel model = embeddingModelInstance;
            EmbeddingStore<TextSegment> store = embeddingStore;

            // 累积所有向量，最后一次性批量写入 ES，减少 ES 写入次数
            List<Embedding> allEmbeddings = new ArrayList<>();
            int totalBatches = (int) Math.ceil((double) textSegments.size() / batchSize);

            for (int i = 0; i < textSegments.size(); i += batchSize) {
                int end = Math.min(i + batchSize, textSegments.size());
                List<TextSegment> batch = textSegments.subList(i, end);

                // 生成向量
                List<Embedding> embeddings = model.embedAll(batch).content();
                allEmbeddings.addAll(embeddings);

                log.debug("文档 {} 向量化批次 {}/{} 完成", documentId, (i / batchSize) + 1, totalBatches);
            }

            // 一次性批量写入 ES
            store.addAll(allEmbeddings, textSegments);
            log.info("文档 {} 向量批量写入 ES 完成，共 {} 条", documentId, allEmbeddings.size());
        }

        // 4. 更新分片状态为 VECTOR_STORED
        List<Long> updateIds = new ArrayList<>();
        for (KnowledgeDocumentSegment segment : toEmbed) {
            updateIds.add(segment.getId());
        }
        for (KnowledgeDocumentSegment segment : skipEmbed) {
            updateIds.add(segment.getId());
        }

        if (!updateIds.isEmpty()) {
            List<KnowledgeDocumentSegment> toUpdate = new ArrayList<>();
            for (Long id : updateIds) {
                KnowledgeDocumentSegment seg = new KnowledgeDocumentSegment();
                seg.setId(id);
                seg.setStatus("VECTOR_STORED");
                toUpdate.add(seg);
            }
            segmentService.updateBatchById(toUpdate);
        }

        int total = toEmbed.size() + skipEmbed.size();
        log.info("文档 {} 向量化存储完成: 向量化 {} 个, 跳过 {} 个", documentId, toEmbed.size(), skipEmbed.size());

        return total;
    }

    /**
     * 删除指定文档在 Elasticsearch 中的所有向量数据
     * <p>通过 metadata.documentId 字段匹配并删除所有相关文档</p>
     *
     * @param documentId 文档ID
     */
    public void deleteByDocumentId(Long documentId) throws URISyntaxException {
        log.info("开始删除文档 {} 的ES向量数据", documentId);
        try {
            var deleteResponse = esClient.deleteByQuery(d -> d
                    .index(esIndexName)
                    .query(q -> q.term(t -> t
                            .field("metadata.documentId")
                            .value(String.valueOf(documentId))
                    ))
            );

            long deleted = deleteResponse.deleted() != null ? deleteResponse.deleted() : 0;
            log.info("文档 {} 的ES向量数据删除完成，共删除 {} 条", documentId, deleted);
        } catch (Exception e) {
            log.error("删除文档 {} 的ES向量数据失败", documentId, e);
            throw new RuntimeException("删除ES向量数据失败: " + e.getMessage(), e);
        }
    }

    /**
     * 批量删除多个文档在 Elasticsearch 中的所有向量数据
     * <p>通过 metadata.documentId 字段的 terms 查询一次性匹配并删除所有相关文档</p>
     *
     * @param documentIds 文档ID列表
     */
    public void deleteByDocumentIds(List<Long> documentIds) throws URISyntaxException {
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }
        log.info("开始批量删除 {} 个文档的ES向量数据", documentIds.size());
        try {
            List<FieldValue> fieldValues = documentIds.stream()
                    .map(id -> FieldValue.of(String.valueOf(id)))
                    .collect(Collectors.toList());

            var deleteResponse = esClient.deleteByQuery(d -> d
                    .index(esIndexName)
                    .query(q -> q.terms(t -> t
                            .field("metadata.documentId")
                            .terms(tt -> tt.value(fieldValues))
                    ))
            );

            long deleted = deleteResponse.deleted() != null ? deleteResponse.deleted() : 0;
            log.info("批量删除ES向量数据完成，共删除 {} 条", deleted);
        } catch (Exception e) {
            log.error("批量删除 {} 个文档的ES向量数据失败", documentIds.size(), e);
            throw new RuntimeException("批量删除ES向量数据失败: " + e.getMessage(), e);
        }
    }

    /**
     * 混合检索：结合向量语义检索 + BM25 关键词检索，应用层手动融合排序
     * <p>分别执行向量语义检索和 BM25 全文检索，使用 RRF（Reciprocal Rank Fusion）算法在应用层融合排序</p>
     * <p>不依赖 Elasticsearch 的 RRF 功能，兼容基础许可证</p>
     *
     * @param query      查询文本
     * @param maxResults 最大返回结果数
     * @param minScore   最小相似度分数（0-1）
     * @param userType   当前用户类型，用于权限过滤（可选，为 null 时不过滤）
     * @return 匹配的文本文段列表，包含分数
     */
    public List<SearchResult> vectorSearch(String query, int maxResults, double minScore, String userType) throws URISyntaxException {
        log.info("混合检索: query={}, maxResults={}, minScore={}, userType={}", query, maxResults, minScore, userType);

        EmbeddingModel model = embeddingModelInstance;
        ElasticsearchEmbeddingStore store = embeddingStore;

        boolean applyPermissionFilter = userType != null && !userType.isBlank();
        // 权限过滤在应用层做，无权结果会白占 top-N 名额，超量召回后再截断回补
        int fetchSize = applyPermissionFilter ? maxResults * PERMISSION_OVERFETCH : maxResults;

        // 将查询文本转换为向量
        Embedding queryEmbedding = model.embed(query).content();

        // 1. 向量语义检索
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(fetchSize)
                .minScore(minScore)
                .build();
        EmbeddingSearchResult<TextSegment> vectorResult = store.search(request);

        // 2. BM25 关键词检索（通过 ES 客户端直接查询）
        List<Bm25Hit> bm25Hits = executeBm25Search(query, fetchSize);

        // 3. 应用层 RRF 融合排序，返回完整候选集（此处不截断）
        List<SearchResult> searchResults = fuseResults(vectorResult, bm25Hits);

        // 4. 权限过滤必须在截断之前，否则被无权文档挤掉的名额无法回补
        if (applyPermissionFilter) {
            searchResults = filterByPermission(searchResults, userType);
        }

        List<SearchResult> finalResults = searchResults.size() > maxResults
                ? new ArrayList<>(searchResults.subList(0, maxResults))
                : searchResults;

        log.info("混合检索完成: 向量检索 {} 条, BM25检索 {} 条, 过滤截断后 {} 条",
                vectorResult.matches().size(), bm25Hits.size(), finalResults.size());
        return finalResults;
    }

    /**
     * 执行 BM25 全文关键词检索
     *
     * @param query      查询文本
     * @param maxResults 最大返回结果数
     * @return BM25 命中结果列表
     */
    private List<Bm25Hit> executeBm25Search(String query, int maxResults) {
        List<Bm25Hit> hits = new ArrayList<>();
        try {
            Query bm25Query = Query.of(q -> q.match(m -> m
                    .field("text")
                    .query(query)
            ));

            SearchResponse<Map> response = esClient.search(s -> s
                    .index(esIndexName)
                    .query(bm25Query)
                    .size(maxResults),
                    Map.class
            );

            for (Hit<Map> hit : response.hits().hits()) {
                if (hit.source() != null) {
                    hits.add(new Bm25Hit(
                            hit.source().get("text") != null ? hit.source().get("text").toString() : "",
                            hit.score() != null ? hit.score() : 0.0,
                            extractMetadata(hit.source())
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("BM25 关键词检索失败，将仅使用向量检索结果: {}", e.getMessage());
        }
        return hits;
    }

    /**
     * 从 ES _source 中取出 metadata 子对象
     * <p>权限过滤依赖其中的 accessibleBy，缺失该字段会让无权文档绕过过滤流出</p>
     */
    private Map<String, Object> extractMetadata(Map source) {
        Map<String, Object> metadata = new HashMap<>();
        if (source == null || !(source.get("metadata") instanceof Map<?, ?> raw)) {
            return metadata;
        }
        raw.forEach((key, value) -> metadata.put(String.valueOf(key), value));
        return metadata;
    }

    /**
     * 使用 RRF（Reciprocal Rank Fusion）算法融合向量检索和 BM25 检索结果
     * <p>RRF 公式: score = Σ 1/(k + rank_i)，其中 k=60 为常用常数</p>
     * <p>返回按 RRF 分数降序的完整候选集，截断由调用方在权限过滤之后执行</p>
     *
     * @param vectorResult 向量检索结果
     * @param bm25Hits     BM25 检索结果
     * @return 融合排序后的结果列表
     */
    private List<SearchResult> fuseResults(EmbeddingSearchResult<TextSegment> vectorResult,
                                           List<Bm25Hit> bm25Hits) {
        // RRF 常数 k，常用值为 60
        final int rrfK = 60;

        // 记录每个文档的 RRF 分数和对应的内容
        // key = 文档文本内容（作为去重标识）
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, Map<String, Object>> metadataMap = new HashMap<>();

        // 向量检索结果按排名贡献 RRF 分数
        for (int rank = 0; rank < vectorResult.matches().size(); rank++) {
            var match = vectorResult.matches().get(rank);
            String text = match.embedded() != null ? match.embedded().text() : "";
            double rrfScore = 1.0 / (rrfK + rank + 1);
            rrfScores.merge(text, rrfScore, Double::sum);
            if (match.embedded() != null && match.embedded().metadata() != null) {
                metadataMap.putIfAbsent(text, match.embedded().metadata().toMap());
            }
        }

        // BM25 检索结果按排名贡献 RRF 分数
        for (int rank = 0; rank < bm25Hits.size(); rank++) {
            Bm25Hit bm25Hit = bm25Hits.get(rank);
            String text = bm25Hit.text();
            double rrfScore = 1.0 / (rrfK + rank + 1);
            rrfScores.merge(text, rrfScore, Double::sum);
            // 仅 BM25 命中的文档也要带上元数据，否则权限过滤拿不到 accessibleBy
            if (!bm25Hit.metadata().isEmpty()) {
                metadataMap.putIfAbsent(text, bm25Hit.metadata());
            }
        }

        // 按 RRF 分数降序排序
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> {
                    SearchResult sr = new SearchResult();
                    sr.setScore(entry.getValue());
                    sr.setText(entry.getKey());
                    sr.setMetadata(metadataMap.get(entry.getKey()));
                    return sr;
                })
                .collect(Collectors.toList());
    }


    /**
     * 根据用户角色过滤检索结果
     * <p>仅保留当前用户有权访问的文档（基于 metadata 中的 accessibleBy 字段）</p>
     *
     * @param results  检索结果列表
     * @param userType 当前用户类型（VISITOR/CUSTOMER/STAFF）
     * @return 过滤后的结果列表
     */
    private List<SearchResult> filterByPermission(List<SearchResult> results, String userType) {
        com.lujia.rag.raglangchain4j.auth.enums.UserType currentUserType =
                com.lujia.rag.raglangchain4j.auth.enums.UserType.fromCode(userType);

        List<SearchResult> filtered = new ArrayList<>();
        for (SearchResult result : results) {
            Map<String, Object> meta = result.getMetadata();
            Object accessibleBy = meta != null ? meta.get(DocumentConstant.ACCESSIBLE_BY) : null;
            // 元数据缺失或无 accessibleBy 一律按"未知级别"处理，只放行 STAFF，
            // 避免因某条链路丢元数据而让受限文档流出
            if (currentUserType.canAccess(accessibleBy != null ? String.valueOf(accessibleBy) : null)) {
                filtered.add(result);
            }
        }
        if (filtered.size() < results.size()) {
            log.info("权限过滤: 用户类型={}, 过滤前 {} 条, 过滤后 {} 条",
                    userType, results.size(), filtered.size());
        }
        return filtered;
    }

    /**
     * BM25 检索结果
     *
     * @param metadata ES _source 中的 metadata 子对象，含 accessibleBy 等文档权限信息
     */
    private record Bm25Hit(String text, double score, Map<String, Object> metadata) {
    }

    /**
     * 检索结果
     */
    public static class SearchResult {
        private double score;
        private String text;
        private java.util.Map<String, Object> metadata;

        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public java.util.Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(java.util.Map<String, Object> metadata) { this.metadata = metadata; }
    }
}
