package com.lujia.rag.raglangchain4j.test;

import com.lujia.rag.raglangchain4j.chat.service.IntentRecognitionService;
import com.lujia.rag.raglangchain4j.chat.service.KnowEngineChatAiService;
import com.lujia.rag.raglangchain4j.chat.service.TitleGeneratorService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 测试用 AI 配置
 * <p>使用 {@link TestConfiguration} 而非 {@code @Configuration}：普通配置会被组件扫描自动加载，
 * 污染其它测试（如 contextLoads）的 Spring 上下文并触发 Bean 定义冲突。
 * 需要的测试类必须显式 {@code @Import(TestAiConfig.class)}</p>
 */
@TestConfiguration
public class TestAiConfig {

    static IntentRecognitionService intentRecognitionServiceMock = org.mockito.Mockito.mock(IntentRecognitionService.class);
    static KnowEngineChatAiService knowEngineChatAiServiceMock = org.mockito.Mockito.mock(KnowEngineChatAiService.class);
    static TitleGeneratorService titleGeneratorServiceMock = org.mockito.Mockito.mock(TitleGeneratorService.class);

    static void resetMocks() {
        intentRecognitionServiceMock = org.mockito.Mockito.mock(IntentRecognitionService.class);
        knowEngineChatAiServiceMock = org.mockito.Mockito.mock(KnowEngineChatAiService.class);
        titleGeneratorServiceMock = org.mockito.Mockito.mock(TitleGeneratorService.class);
    }

    @Bean
    @Primary
    public EmbeddingModel testEmbeddingModel() {
        return new FakeEmbeddingModel();
    }

    @Bean
    @Primary
    public ContentAggregator contentAggregator() {
        return (Map<Query, Collection<List<Content>>> queryToContents) ->
                queryToContents.values().stream()
                        .flatMap(Collection::stream)
                        .flatMap(List::stream)
                        .collect(Collectors.toList());
    }

    @Bean
    @Primary
    public IntentRecognitionService intentRecognitionService() {
        return intentRecognitionServiceMock;
    }

    @Bean
    @Primary
    public KnowEngineChatAiService knowEngineChatAiService() {
        return knowEngineChatAiServiceMock;
    }

    @Bean
    @Primary
    public TitleGeneratorService titleGeneratorService() {
        return titleGeneratorServiceMock;
    }
}
