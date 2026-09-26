package com.lujia.rag.raglangchain4j.test;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用假 Embedding 模型
 * <p>生成确定性向量（基于文本哈希），用于集成测试中替代真实百炼 Embedding API，
 * 保证相同文本产生相同向量，不同文本产生不同向量</p>
 */
public class FakeEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSION = 8;

    @Override
    public Response<Embedding> embed(String text) {
        float[] vector = generateVector(text);
        return Response.from(Embedding.from(vector));
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        List<Embedding> embeddings = new ArrayList<>();
        for (TextSegment segment : segments) {
            float[] vector = generateVector(segment.text());
            embeddings.add(Embedding.from(vector));
        }
        return Response.from(embeddings);
    }

    /**
     * 基于文本哈希生成确定性向量，相同文本产生相同向量
     */
    private float[] generateVector(String text) {
        float[] vector = new float[DIMENSION];
        int hash = text.hashCode();
        for (int i = 0; i < DIMENSION; i++) {
            hash = hash * 31 + i;
            vector[i] = (float) (hash % 1000) / 1000.0f;
        }
        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < DIMENSION; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }
}
