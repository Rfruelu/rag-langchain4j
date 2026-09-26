package com.lujia.rag.raglangchain4j.document.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * MinerU 文档解析服务
 * <p>封装与 MinerU API 的交互，提供文档解析能力。MinerU 是一个高质量的文档解析工具，
 * 可将 PDF、图片等文档转换为 Markdown 格式。</p>
 * <p>典型使用流程：</p>
 * <ol>
 *   <li>调用 {@link #createExtractTask(String)} 创建解析任务</li>
 *   <li>轮询调用 {@link #queryTaskResult(String)} 查询任务状态</li>
 *   <li>任务完成后调用 {@link #downloadZipFile(String)} 下载解析结果</li>
 * </ol>
 *
 * @see <a href="https://github.com/opendatalab/MinerU">MinerU GitHub</a>
 */
@Slf4j
@Service
public class MineruService {

    /** MinerU API 地址 */
    @Value("${mineru.api-url}")
    private String apiUrl;

    /** MinerU API 认证令牌 */
    @Value("${mineru.token}")
    private String token;

    /** 模型版本，默认使用 vlm（视觉语言模型） */
    @Value("${mineru.model-version:vlm}")
    private String modelVersion;

    /** HTTP 客户端，用于调用 MinerU API */
    private final CloseableHttpClient httpClient;

    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构造函数，初始化 HTTP 客户端
     * <p>配置连接超时 30 秒，响应超时 60 秒</p>
     */
    public MineruService() {
        this.httpClient = HttpClients.custom()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofSeconds(30))
                        .setResponseTimeout(Timeout.ofSeconds(60))
                        .build())
                .build();
    }

    /**
     * 销毁时关闭 HTTP 客户端，释放资源
     */
    @PreDestroy
    public void destroy() {
        try {
            httpClient.close();
        } catch (Exception e) {
            log.error("关闭 HttpClient 失败", e);
        }
    }

    /**
     * 创建文档解析任务
     * <p>将文件 URL 提交给 MinerU，返回任务 ID 用于后续查询</p>
     *
     * @param fileUrl 文件 URL（MinerU 可访问的地址，如 MinIO 外部地址）
     * @return task_id 解析任务ID
     * @throws RuntimeException 创建任务失败时抛出
     */
    public String createExtractTask(String fileUrl) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("url", fileUrl);
            body.put("model_version", modelVersion);

            HttpPost httpPost = new HttpPost(apiUrl + "/api/v4/extract/task");
            httpPost.setHeader("Authorization", "Bearer " + token);
            httpPost.setHeader("Content-Type", "application/json");
            httpPost.setEntity(new StringEntity(objectMapper.writeValueAsString(body), ContentType.APPLICATION_JSON));

            return httpClient.execute(httpPost, response -> {
                String responseBody = EntityUtils.toString(response.getEntity());
                log.info("MinerU 创建任务响应: {}", responseBody);
                JsonNode json = objectMapper.readTree(responseBody);
                if (json.get("code").asInt() != 0) {
                    throw new RuntimeException("MinerU 创建任务失败: " + json.get("msg").asText());
                }
                return json.get("data").get("task_id").asText();
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("MinerU 创建任务异常", e);
            throw new RuntimeException("MinerU 创建任务失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询解析任务结果
     * <p>根据任务 ID 查询当前任务状态和结果</p>
     *
     * @param taskId 任务ID
     * @return 包含以下信息的 Map：
     *         <ul>
     *           <li>state - 任务状态：pending（等待中）、running（执行中）、completed（已完成）、failed（失败）</li>
     *           <li>full_zip_url - 解析结果下载地址（仅 completed 状态时存在）</li>
     *           <li>err_msg - 错误信息（仅 failed 状态时存在）</li>
     *         </ul>
     * @throws RuntimeException 查询失败时抛出
     */
    public Map<String, String> queryTaskResult(String taskId) {
        try {
            HttpGet httpGet = new HttpGet(apiUrl + "/api/v4/extract/task/" + taskId);
            httpGet.setHeader("Authorization", "Bearer " + token);

            return httpClient.execute(httpGet, response -> {
                String responseBody = EntityUtils.toString(response.getEntity());
                log.info("MinerU 查询任务 {} 响应: {}", taskId, responseBody);
                JsonNode json = objectMapper.readTree(responseBody);
                if (json.get("code").asInt() != 0) {
                    throw new RuntimeException("MinerU 查询任务失败: " + json.get("msg").asText());
                }
                JsonNode data = json.get("data");
                Map<String, String> result = new HashMap<>();
                result.put("state", data.get("state").asText());
                if (data.has("full_zip_url") && !data.get("full_zip_url").isNull()) {
                    result.put("full_zip_url", data.get("full_zip_url").asText());
                }
                if (data.has("err_msg") && !data.get("err_msg").isNull()) {
                    result.put("err_msg", data.get("err_msg").asText());
                }
                return result;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("MinerU 查询任务异常, taskId: {}", taskId, e);
            throw new RuntimeException("MinerU 查询任务失败: " + e.getMessage(), e);
        }
    }

    /**
     * 下载解析结果 zip 文件
     * <p>从 MinerU 返回的 URL 下载解析后的 zip 文件，包含 Markdown 内容和图片</p>
     *
     * @param zipUrl zip 文件下载地址
     * @return 文件输入流（ByteArrayInputStream）
     * @throws RuntimeException 下载失败时抛出
     */
    public InputStream downloadZipFile(String zipUrl) {
        try {
            HttpGet httpGet = new HttpGet(URI.create(zipUrl));
            return httpClient.execute(httpGet, response -> {
                byte[] bytes = EntityUtils.toByteArray(response.getEntity());
                log.info("MinerU 解析结果下载成功, 大小: {} bytes", bytes.length);
                return new java.io.ByteArrayInputStream(bytes);
            });
        } catch (Exception e) {
            log.error("MinerU 下载解析结果失败, url: {}", zipUrl, e);
            throw new RuntimeException("下载解析结果失败: " + e.getMessage(), e);
        }
    }
}
