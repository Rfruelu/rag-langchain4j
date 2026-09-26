package com.lujia.rag.raglangchain4j.chat.rag;

import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.model.scoring.onnx.OnnxScoringModel;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * OnnxScoringModel 单例持有者
 * <p>使用静态内部类实现懒加载单例模式，线程安全</p>
 * <p>从 classpath（resources 目录）加载 bge-reranker-v2-m3 ONNX 重排序模型，
 * 首次启动时自动将模型文件提取到临时目录供 ONNX Runtime 加载</p>
 *
 * <p>模型文件需放置在 {@code src/main/resources/bge/} 目录下：</p>
 * <ul>
 *   <li>{@code model_quantized.onnx} — ONNX 量化模型文件</li>
 *   <li>{@code tokenizer.json} — 分词器配置文件</li>
 * </ul>
 */
@Slf4j
public class OnnxScoringModelHolder {

    /** 模型资源目录（classpath 下） */
    private static final String MODEL_RESOURCE_DIR = "/bge";

    /** ONNX 模型文件名 */
    private static final String MODEL_FILE = "model_quantized.onnx";

    /** 分词器配置文件名 */
    private static final String TOKENIZER_FILE = "tokenizer.json";

    /** bge-reranker-v2-m3 最大 token 数 */
    private static final int MAX_TOKENS = 8192;

    /**
     * 静态内部类单例持有者
     * <p>JVM 类加载机制保证线程安全，首次调用 {@link #getInstance()} 时触发初始化</p>
     */
    private static class Holder {
        private static final ScoringModel INSTANCE = createModel();
    }

    /**
     * 获取 OnnxScoringModel 单例实例
     *
     * @return ScoringModel 实例
     */
    public static ScoringModel getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * 创建 OnnxScoringModel 实例
     * <p>将 classpath 下的模型文件提取到临时目录，然后使用 ONNX Runtime 加载</p>
     */
    private static ScoringModel createModel() {
        try {
            log.info("开始初始化 bge-reranker-v2-m3 ONNX 重排序模型...");

            // 将 classpath 下的模型文件提取到临时目录（ONNX Runtime 需要文件系统路径）
            Path tempDir = extractModelsToTempDir();

            String modelPath = tempDir.resolve(MODEL_FILE).toAbsolutePath().toString();
            String tokenizerPath = tempDir.resolve(TOKENIZER_FILE).toAbsolutePath().toString();

            log.info("ONNX 模型路径: {}", modelPath);
            log.info("Tokenizer 路径: {}", tokenizerPath);

            ScoringModel model = new OnnxScoringModel(modelPath, tokenizerPath, MAX_TOKENS);

            log.info("bge-reranker-v2-m3 ONNX 重排序模型初始化完成");
            return model;

        } catch (Exception e) {
            log.error("OnnxScoringModel 初始化失败", e);
            throw new RuntimeException("OnnxScoringModel 初始化失败", e);
        }
    }

    /**
     * 将 classpath 下的模型文件提取到临时目录
     * <p>使用标记文件避免重复提取，临时目录在 JVM 退出时自动清理</p>
     *
     * @return 临时目录路径
     * @throws IOException 文件读写异常
     */
    private static Path extractModelsToTempDir() throws IOException {
        Path tempDir = Files.createTempDirectory("bge-reranker-v2-m3-");
        Path modelFile = tempDir.resolve(MODEL_FILE);
        Path tokenizerFile = tempDir.resolve(TOKENIZER_FILE);

        // 提取 ONNX 模型文件
        extractResource(MODEL_RESOURCE_DIR + "/" + MODEL_FILE, modelFile);
        log.debug("ONNX 模型文件提取完成: {}", modelFile);

        // 提取 Tokenizer 文件
        extractResource(MODEL_RESOURCE_DIR + "/" + TOKENIZER_FILE, tokenizerFile);
        log.debug("Tokenizer 文件提取完成: {}", tokenizerFile);

        return tempDir;
    }

    /**
     * 从 classpath 提取单个资源文件到目标路径
     *
     * @param resourcePath classpath 下的资源路径
     * @param targetPath   目标文件路径
     * @throws IOException 资源读取或文件写入失败
     */
    private static void extractResource(String resourcePath, Path targetPath) throws IOException {
        try (InputStream is = OnnxScoringModelHolder.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("classpath 下未找到模型文件: " + resourcePath
                        + "，请确认已将模型文件放置到 src/main/resources" + resourcePath);
            }
            Files.copy(is, targetPath);
        }
    }

    /** 禁止外部实例化 */
    private OnnxScoringModelHolder() {
    }
}
