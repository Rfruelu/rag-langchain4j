package com.lujia.rag.raglangchain4j.test;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lujia.rag.raglangchain4j.chat.entity.RagChatConversation;
import com.lujia.rag.raglangchain4j.chat.entity.RagChatMessage;
import com.lujia.rag.raglangchain4j.chat.enums.ChatIntent;
import com.lujia.rag.raglangchain4j.chat.mapper.RagChatConversationMapper;
import com.lujia.rag.raglangchain4j.chat.mapper.RagChatMessageMapper;
import com.lujia.rag.raglangchain4j.chat.service.impl.RagChatServiceImpl;
import com.lujia.rag.raglangchain4j.document.entity.KnowledgeDocumentSegment;
import com.lujia.rag.raglangchain4j.document.entity.RagKnowledgeDocument;
import com.lujia.rag.raglangchain4j.document.mapper.KnowledgeDocumentSegmentMapper;
import com.lujia.rag.raglangchain4j.document.mapper.RagKnowledgeDocumentMapper;
import com.lujia.rag.raglangchain4j.document.service.VectorStoreService;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * 核心链路集成测试
 * <p>使用 Testcontainers 起 MySQL/Redis/ES/MinIO 容器，覆盖"上传 → 向量化 → 检索问答"全链路</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Import(TestAiConfig.class)
class FullPipelineIntegrationTest {

    private static final String ES_INDEX_NAME = "rag-documents-test";

    private static final String ES_INDEX_MAPPING = """
            {
              "mappings": {
                "properties": {
                  "vector": {
                    "type": "dense_vector",
                    "dims": 8,
                    "index": true,
                    "similarity": "cosine"
                  },
                  "text": {
                    "type": "text"
                  },
                  "metadata": {
                    "type": "object"
                  }
                }
              }
            }
            """;

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:latest"))
            .withDatabaseName("rag_db")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("init.sql")
            .withStartupTimeout(Duration.ofMinutes(3));

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:latest"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    static final ElasticsearchContainer elasticsearch = new ElasticsearchContainer(
            DockerImageName.parse("elasticsearch:9.2.8"))
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withStartupTimeout(Duration.ofMinutes(3));

    @Container
    static final GenericContainer<?> minio = new GenericContainer<>(DockerImageName.parse("elestio/minio:latest"))
            .withExposedPorts(9000)
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", "test")
            .withEnv("MINIO_ROOT_PASSWORD", "test12345")
            .waitingFor(Wait.forHttp("/minio/health/live"))
            .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.url", () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));

        registry.add("elasticsearch.url", () -> "http://" + elasticsearch.getHttpHostAddress());

        registry.add("minio.endpoint", () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
    }

    @BeforeAll
    static void createElasticsearchIndex() throws Exception {
        recreateEsIndex();
    }

    @Autowired
    private RagKnowledgeDocumentMapper documentMapper;

    @Autowired
    private KnowledgeDocumentSegmentMapper segmentMapper;

    @Autowired
    private RagChatConversationMapper conversationMapper;

    @Autowired
    private RagChatMessageMapper messageMapper;

    @Autowired
    private VectorStoreService vectorStoreService;

    @Autowired
    private RagChatServiceImpl ragChatService;

    @MockitoBean(name = "generalChatModel")
    private StreamingChatModel generalChatModel;

    @MockitoBean(name = "neo4jDriver")
    private Driver neo4jDriver;

    @MockitoBean(name = "neo4jContentRetriever")
    private ContentRetriever neo4jContentRetriever;

    @BeforeEach
    void cleanData() throws Exception {
        segmentMapper.delete(new LambdaQueryWrapper<>());
        documentMapper.delete(new LambdaQueryWrapper<>());
        messageMapper.delete(new LambdaQueryWrapper<>());
        conversationMapper.delete(new LambdaQueryWrapper<>());

        recreateEsIndex();

        // 重置 mock（不创建新实例，因为 Spring 缓存了 Bean 引用）
        reset(TestAiConfig.intentRecognitionServiceMock,
                TestAiConfig.knowEngineChatAiServiceMock,
                TestAiConfig.titleGeneratorServiceMock);
        reset(generalChatModel, neo4jDriver, neo4jContentRetriever);
    }

    private void configureMocks(ChatIntent intent) {
        when(TestAiConfig.intentRecognitionServiceMock.recognizeIntent(any(), any()))
                .thenReturn(intent);
        when(TestAiConfig.knowEngineChatAiServiceMock.chat(any(), any()))
                .thenReturn("AI回复内容");
        when(TestAiConfig.titleGeneratorServiceMock.generateTitle(any(), any()))
                .thenReturn("测试会话标题");

        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("测试回复");
            handler.onCompleteResponse(null);
            return null;
        }).when(generalChatModel).chat(any(List.class), any());
    }

    @Test
    @DisplayName("文档向量化 → ES 存储 → 检索命中：验证上传到检索全链路")
    void testVectorizeAndRetrieve() throws Exception {
        RagKnowledgeDocument doc = new RagKnowledgeDocument();
        doc.setTitle("全棉面料产品手册");
        doc.setFileType("md");
        doc.setStatus("CHUNKED");
        doc.setAccessibleBy("STAFF");
        doc.setChunkSize(500);
        doc.setOverlap(50);
        documentMapper.insert(doc);

        KnowledgeDocumentSegment seg1 = new KnowledgeDocumentSegment();
        seg1.setDocumentId(doc.getId());
        seg1.setText("32支精梳全棉平纹布，克重180g/m²，适用于夏季T恤和 Polo 衫。色牢度4级以上，缩水率小于3%。");
        seg1.setChunkId("chunk-001");
        seg1.setChunkOrder(1);
        seg1.setStatus("STORED");
        seg1.setSkipEmbedding(0);
        segmentMapper.insert(seg1);

        KnowledgeDocumentSegment seg2 = new KnowledgeDocumentSegment();
        seg2.setDocumentId(doc.getId());
        seg2.setText("40支密棉奥代尔拉架卫衣布，克重280g/m²，适用于春秋卫衣和运动裤。手感柔软，弹性好。");
        seg2.setChunkId("chunk-002");
        seg2.setChunkOrder(2);
        seg2.setStatus("STORED");
        seg2.setSkipEmbedding(0);
        segmentMapper.insert(seg2);

        KnowledgeDocumentSegment seg3 = new KnowledgeDocumentSegment();
        seg3.setDocumentId(doc.getId());
        seg3.setText("不倒绒保暖内衣面料，双面绒结构，内层拉毛处理，克重350g/m²，适用于冬季保暖内衣。");
        seg3.setChunkId("chunk-003");
        seg3.setChunkOrder(3);
        seg3.setStatus("STORED");
        seg3.setSkipEmbedding(0);
        segmentMapper.insert(seg3);

        int vectorized = vectorStoreService.vectorizeAndStore(doc.getId());
        assertThat(vectorized).isEqualTo(3);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<VectorStoreService.SearchResult> results =
                    vectorStoreService.vectorSearch("全棉T恤面料", 10, 0.0, "STAFF");
            assertThat(results).isNotEmpty();
        });

        List<VectorStoreService.SearchResult> results =
                vectorStoreService.vectorSearch("全棉T恤面料", 10, 0.0, "STAFF");
        assertThat(results).isNotEmpty();
        boolean foundCotton = results.stream()
                .anyMatch(r -> r.getText().contains("全棉") || r.getText().contains("T恤"));
        assertThat(foundCotton).as("应检索到全棉面料相关内容").isTrue();

        List<VectorStoreService.SearchResult> fleeceResults =
                vectorStoreService.vectorSearch("不倒绒保暖内衣", 10, 0.0, "STAFF");
        assertThat(fleeceResults).isNotEmpty();
        boolean foundFleece = fleeceResults.stream()
                .anyMatch(r -> r.getText().contains("不倒绒") || r.getText().contains("保暖"));
        assertThat(foundFleece).as("应检索到不倒绒相关内容").isTrue();
    }

    @Test
    @DisplayName("权限过滤：不同用户类型检索到不同范围的文档")
    void testPermissionFiltering() throws Exception {
        RagKnowledgeDocument staffDoc = new RagKnowledgeDocument();
        staffDoc.setTitle("内部客服操作手册");
        staffDoc.setFileType("md");
        staffDoc.setStatus("CHUNKED");
        staffDoc.setAccessibleBy("STAFF");
        documentMapper.insert(staffDoc);

        KnowledgeDocumentSegment staffSeg = new KnowledgeDocumentSegment();
        staffSeg.setDocumentId(staffDoc.getId());
        staffSeg.setText("客服内部操作流程：处理退货时需要核实订单号、退货原因和物流信息。");
        staffSeg.setChunkId("chunk-staff-001");
        staffSeg.setChunkOrder(1);
        staffSeg.setStatus("STORED");
        staffSeg.setSkipEmbedding(0);
        segmentMapper.insert(staffSeg);

        RagKnowledgeDocument visitorDoc = new RagKnowledgeDocument();
        visitorDoc.setTitle("面料选购指南");
        visitorDoc.setFileType("md");
        visitorDoc.setStatus("CHUNKED");
        visitorDoc.setAccessibleBy("VISITOR");
        documentMapper.insert(visitorDoc);

        KnowledgeDocumentSegment visitorSeg = new KnowledgeDocumentSegment();
        visitorSeg.setDocumentId(visitorDoc.getId());
        visitorSeg.setText("面料选购基础知识：全棉面料透气舒适，适合夏季服装。");
        visitorSeg.setChunkId("chunk-visitor-001");
        visitorSeg.setChunkOrder(1);
        visitorSeg.setStatus("STORED");
        visitorSeg.setSkipEmbedding(0);
        segmentMapper.insert(visitorSeg);

        vectorStoreService.vectorizeAndStore(staffDoc.getId());
        vectorStoreService.vectorizeAndStore(visitorDoc.getId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<VectorStoreService.SearchResult> staffResults =
                    vectorStoreService.vectorSearch("面料", 10, 0.0, "STAFF");
            assertThat(staffResults).hasSizeGreaterThanOrEqualTo(2);
        });

        List<VectorStoreService.SearchResult> staffResults =
                vectorStoreService.vectorSearch("面料", 10, 0.0, "STAFF");
        assertThat(staffResults).hasSizeGreaterThanOrEqualTo(2);

        List<VectorStoreService.SearchResult> visitorResults =
                vectorStoreService.vectorSearch("面料", 10, 0.0, "VISITOR");
        boolean hasStaffContent = visitorResults.stream()
                .anyMatch(r -> r.getText().contains("客服内部操作流程"));
        assertThat(hasStaffContent).as("VISITOR 用户不应检索到 STAFF 级别文档").isFalse();
    }

    @Test
    @DisplayName("对话链路：发送消息 → 意图识别 → RAG 检索 → 生成回复")
    void testChatWithRagPipeline() throws Exception {
        RagKnowledgeDocument doc = new RagKnowledgeDocument();
        doc.setTitle("产品价格表");
        doc.setFileType("md");
        doc.setStatus("CHUNKED");
        doc.setAccessibleBy("STAFF");
        documentMapper.insert(doc);

        KnowledgeDocumentSegment seg = new KnowledgeDocumentSegment();
        seg.setDocumentId(doc.getId());
        seg.setText("精梳全棉32支平纹布剪版布价格：25元/米，大货价格：18元/米。MOQ 100米起。");
        seg.setChunkId("chunk-price-001");
        seg.setChunkOrder(1);
        seg.setStatus("STORED");
        seg.setSkipEmbedding(0);
        segmentMapper.insert(seg);

        vectorStoreService.vectorizeAndStore(doc.getId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<VectorStoreService.SearchResult> results =
                    vectorStoreService.vectorSearch("全棉布价格", 10, 0.0, "STAFF");
            assertThat(results).isNotEmpty();
        });

        configureMocks(ChatIntent.PRICE_INQUIRY);
        when(TestAiConfig.knowEngineChatAiServiceMock.chat(any(), any()))
                .thenReturn("精梳全棉32支平纹布剪版布价格25元/米，大货价格18元/米，MOQ 100米起。");

        RagChatConversation conversation = ragChatService.createConversation("test-user", "新对话");
        assertThat(conversation.getConversationId()).isNotBlank();

        RagChatMessage reply = ragChatService.sendMessage(
                conversation.getConversationId(), "全棉32支平纹布多少钱？", "STAFF");

        assertThat(reply).isNotNull();
        assertThat(reply.getType()).isEqualTo("ASSISTANT");
        assertThat(reply.getContent()).isNotBlank();

        List<RagChatMessage> messages = ragChatService.listMessages(conversation.getConversationId());
        assertThat(messages).hasSizeGreaterThanOrEqualTo(2);
        assertThat(messages.stream().anyMatch(m -> "USER".equals(m.getType()))).isTrue();
        assertThat(messages.stream().anyMatch(m -> "ASSISTANT".equals(m.getType()))).isTrue();
    }

    @Test
    @DisplayName("通用闲聊：意图为 GENERAL 时走通用模型，不触发 RAG 检索")
    void testGeneralChat() {
        configureMocks(ChatIntent.GENERAL);

        RagChatConversation conversation = ragChatService.createConversation("test-user", "新对话");

        RagChatMessage reply = ragChatService.sendMessage(
                conversation.getConversationId(), "你好，今天天气怎么样？", "STAFF");

        assertThat(reply).isNotNull();
        assertThat(reply.getType()).isEqualTo("ASSISTANT");
        assertThat(reply.getContent()).isNotBlank();
    }

    @Test
    @DisplayName("向量化状态流转：STORED → VECTOR_STORED")
    void testSegmentStatusTransition() throws Exception {
        RagKnowledgeDocument doc = new RagKnowledgeDocument();
        doc.setTitle("状态流转测试文档");
        doc.setFileType("md");
        doc.setStatus("CHUNKED");
        doc.setAccessibleBy("STAFF");
        documentMapper.insert(doc);

        KnowledgeDocumentSegment seg = new KnowledgeDocumentSegment();
        seg.setDocumentId(doc.getId());
        seg.setText("测试文本内容");
        seg.setChunkId("chunk-status-001");
        seg.setChunkOrder(1);
        seg.setStatus("STORED");
        seg.setSkipEmbedding(0);
        segmentMapper.insert(seg);

        assertThat(seg.getStatus()).isEqualTo("STORED");

        vectorStoreService.vectorizeAndStore(doc.getId());

        KnowledgeDocumentSegment updated = segmentMapper.selectById(seg.getId());
        assertThat(updated.getStatus()).isEqualTo("VECTOR_STORED");
    }

    /**
     * 向量检索与 BM25 检索并联，两路都必须把 metadata 带出来，否则权限过滤无依据可判。
     * <p>假模型的向量近乎共线、minScore 挡不住任何文档，因此用 maxResults=1（超量取回 3 条）
     * 配 4 条同向干扰文档，把目标分片挤出向量检索 top-N，使它只能由 BM25 命中</p>
     */
    @Test
    @DisplayName("仅 BM25 命中的分片也必须带出 metadata，否则会被权限过滤误伤")
    void testBm25OnlyHitStillCarriesMetadataForPermissionCheck() throws Exception {
        float[] queryVector = embeddingOf("BM25ONLY-9000");
        for (int i = 0; i < 4; i++) {
            // 干扰文档：与查询向量同向（cosine=1）但正文不含关键词，只会被向量检索带回
            indexRawEsDoc("noise-" + i, "售前话术说明第" + i + "篇，与编号无关", queryVector,
                    "{\"accessibleBy\":\"STAFF\"}");
        }
        // 目标文档：向量取与查询最正交的单位向量，落在向量检索 top-3 之外，只能由 BM25 带回
        indexRawEsDoc("bm25-only", "含编号 BM25ONLY-9000 的公开说明", orthogonalTo(queryVector),
                "{\"accessibleBy\":\"VISITOR\"}");

        List<VectorStoreService.SearchResult> results =
                vectorStoreService.vectorSearch("BM25ONLY-9000", 1, 0.0, "CUSTOMER");

        assertThat(results).as("目标分片只能由 BM25 命中，且须穿过权限过滤与截断存活").hasSize(1);
        assertThat(results.get(0).getText()).contains("BM25ONLY-9000");
        assertThat(results.get(0).getMetadata())
                .as("BM25 分支若丢失 metadata，级别判不出会 fail-closed 误伤公开文档").isNotNull();
        assertThat(results.get(0).getMetadata().get("accessibleBy")).isEqualTo("VISITOR");
    }

    /**
     * 历史索引数据可能完全没有 accessibleBy，这种"级别未知"必须按最严格处理，只放行 STAFF
     */
    @Test
    @DisplayName("metadata 缺失 accessibleBy 的历史数据只放行 STAFF")
    void testUnknownPermissionLevelFailsClosed() throws Exception {
        String query = "LEGACY-7788 未标注权限的历史分片";
        indexRawEsDoc("legacy-no-level", query, embeddingOf(query), "{\"documentId\":\"7\"}");

        List<VectorStoreService.SearchResult> staffResults =
                vectorStoreService.vectorSearch(query, 5, 0.0, "STAFF");
        assertThat(staffResults).as("级别未知的分片仍对 STAFF 可见").hasSize(1);
        assertThat(staffResults.get(0).getMetadata()).isNotNull();

        List<VectorStoreService.SearchResult> visitorResults =
                vectorStoreService.vectorSearch(query, 5, 0.0, "VISITOR");
        assertThat(visitorResults).as("权限级别未知时分片不得外泄").isEmpty();
    }

    /**
     * 用与生产相同的假模型算出确定性查询向量，用于构造"必然命中/必然不命中"的向量检索结果
     */
    private static float[] embeddingOf(String text) {
        return new FakeEmbeddingModel().embed(text).content().vector();
    }

    /**
     * 取与查询向量夹角最大的单位坐标向量，保证该分片在向量检索里排最后
     */
    private static float[] orthogonalTo(float[] queryVector) {
        int minIndex = 0;
        for (int i = 1; i < queryVector.length; i++) {
            if (Math.abs(queryVector[i]) < Math.abs(queryVector[minIndex])) {
                minIndex = i;
            }
        }
        float[] result = new float[queryVector.length];
        result[minIndex] = 1.0f;
        return result;
    }

    /**
     * 绕过业务写入链路，直接往 ES 里放一条文档，用于构造正常的上传流程造不出的索引数据
     */
    private static void indexRawEsDoc(String id, String text, float[] vector, String metadataJson) throws Exception {
        StringBuilder numbers = new StringBuilder();
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                numbers.append(',');
            }
            numbers.append(vector[i]);
        }
        String body = "{\"text\":\"" + text + "\",\"vector\":[" + numbers + "],\"metadata\":" + metadataJson + "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + elasticsearch.getHttpHostAddress()
                        + "/" + ES_INDEX_NAME + "/_doc/" + id + "?refresh=true"))
                .header("Content-Type", "application/json; charset=utf-8")
                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("写入 ES 失败: %s", response.body()).isEqualTo(201);
    }

    /**
     * 删除并重建 ES 索引，确保每个测试用例从干净的索引开始
     */
    private static void recreateEsIndex() throws Exception {
        String esUrl = "http://" + elasticsearch.getHttpHostAddress();
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest deleteRequest = HttpRequest.newBuilder()
                .uri(URI.create(esUrl + "/" + ES_INDEX_NAME))
                .DELETE()
                .build();
        client.send(deleteRequest, HttpResponse.BodyHandlers.ofString());

        HttpRequest createRequest = HttpRequest.newBuilder()
                .uri(URI.create(esUrl + "/" + ES_INDEX_NAME))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(ES_INDEX_MAPPING))
                .build();
        HttpResponse<String> response = client.send(createRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            System.err.println("Failed to create ES index: " + response.body());
        }
    }
}
