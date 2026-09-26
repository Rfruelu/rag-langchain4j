package com.lujia.rag.raglangchain4j.document.service;

import io.minio.*;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * MinIO 文件存储服务
 * <p>封装与 MinIO 对象存储的交互，提供文件上传、下载、删除等操作</p>
 * <p>支持内外部 Endpoint 分离，用于区分应用内部访问和外部服务（如 MinerU）访问</p>
 */
@Slf4j
@Service
public class MinioService {

    /** MinIO 客户端实例 */
    @Autowired
    private MinioClient minioClient;

    /** 存储桶名称 */
    @Value("${minio.bucket-name}")
    private String bucketName;

    /** 内部 Endpoint，用于应用内部访问 MinIO */
    @Value("${minio.endpoint}")
    private String endpoint;

    /** 外部 Endpoint，供 MinerU 等外部服务访问，默认与 endpoint 相同 */
    @Value("${minio.external-endpoint:${minio.endpoint}}")
    private String externalEndpoint;

    /**
     * 上传 MultipartFile 文件到 MinIO
     * <p>自动生成唯一文件路径：日期目录/yyyy/MM/dd/ + UUID + 原始扩展名</p>
     *
     * @param file 上传的文件
     * @return 文件在 MinIO 中的访问 URL（使用内部 endpoint）
     * @throws RuntimeException 上传失败时抛出
     */
    public String uploadFile(MultipartFile file) {
        try {
            // 生成唯一文件名：日期目录 + UUID + 原始扩展名
            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String objectName = datePath + "/" + UUID.randomUUID().toString().replace("-", "") + extension;

            // 上传文件
            try (InputStream inputStream = file.getInputStream()) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucketName)
                                .object(objectName)
                                .stream(inputStream, file.getSize(), -1)
                                .contentType(file.getContentType())
                                .build()
                );
            }

            // 返回文件 URL
            String fileUrl = endpoint + "/" + bucketName + "/" + objectName;
            log.info("文件上传成功: {}", fileUrl);
            return fileUrl;

        } catch (Exception e) {
            log.error("文件上传失败", e);
            throw new RuntimeException("文件上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * 上传输入流到 MinIO
     * <p>适用于程序生成的文件或下载后转存的文件</p>
     *
     * @param inputStream 文件输入流
     * @param objectName  对象名称（MinIO 中的完整路径，如 2024/01/01/xxx.pdf）
     * @param contentType 内容类型（如 application/pdf、image/png）
     * @return 文件在 MinIO 中的访问 URL（使用内部 endpoint）
     * @throws RuntimeException 上传失败时抛出
     */
    public String uploadFile(InputStream inputStream, String objectName, String contentType) {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(inputStream, -1, 10485760)
                            .contentType(contentType)
                            .build()
            );
            String fileUrl = endpoint + "/" + bucketName + "/" + objectName;
            log.info("文件上传成功: {}", fileUrl);
            return fileUrl;
        } catch (Exception e) {
            log.error("文件上传失败", e);
            throw new RuntimeException("文件上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取外部可访问的 Endpoint
     * <p>供 MinerU 等外部服务使用，当 MinIO 部署在内网但需要被外部访问时，
     * 可通过配置 minio.external-endpoint 指定外部可访问的地址</p>
     *
     * @return 外部 Endpoint 地址
     */
    public String getExternalEndpoint() {
        return externalEndpoint;
    }

    /**
     * 获取内部 Endpoint
     * <p>用于应用内部访问 MinIO，如生成文件访问 URL</p>
     *
     * @return 内部 Endpoint 地址
     */
    public String getInternalEndpoint() {
        return endpoint;
    }

    /**
     * 获取存储桶名称
     *
     * @return 存储桶名称
     */
    public String getBucketName() {
        return bucketName;
    }

    /**
     * 删除 MinIO 中的文件
     * <p>根据文件 URL 解析出对象名称后删除，删除失败仅记录日志不抛出异常</p>
     *
     * @param fileUrl 文件 URL（由本服务生成的完整 URL）
     */
    public void deleteFile(String fileUrl) {
        try {
            String objectName = extractObjectName(fileUrl);
            if (objectName == null) {
                log.warn("无法解析文件URL: {}", fileUrl);
                return;
            }
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build()
            );
            log.info("文件删除成功: {}", objectName);
        } catch (Exception e) {
            log.error("文件删除失败: {}", fileUrl, e);
        }
    }

    /**
     * 批量删除 MinIO 中的文件
     * <p>根据文件 URL 解析出对象名称后批量删除，单个对象删除失败仅记录日志不抛出异常</p>
     *
     * @param fileUrls 文件 URL 列表（由本服务生成的完整 URL）
     */
    public void deleteFiles(List<String> fileUrls) {
        List<DeleteObject> objects = fileUrls.stream()
                .map(this::extractObjectName)
                .filter(Objects::nonNull)
                .map(DeleteObject::new)
                .toList();
        if (objects.isEmpty()) {
            log.warn("没有可解析的 MinIO 对象，跳过批量删除: urls={}", fileUrls);
            return;
        }
        try {
            Iterable<Result<DeleteError>> results = minioClient.removeObjects(
                    RemoveObjectsArgs.builder()
                            .bucket(bucketName)
                            .objects(objects)
                            .build()
            );
            for (Result<DeleteError> result : results) {
                DeleteError error = result.get();
                log.error("批量删除 MinIO 对象失败: object={}, message={}", error.objectName(), error.message());
            }
            log.info("MinIO 批量删除完成: count={}", objects.size());
        } catch (Exception e) {
            log.error("MinIO 批量删除失败", e);
        }
    }

    /**
     * 从文件 URL 中提取 MinIO 对象名称
     * <p>例如：http://localhost:9000/bucket/2024/01/01/xxx.pdf → 2024/01/01/xxx.pdf</p>
     * <p>同时支持内部 Endpoint 和外部 Endpoint 两种 URL 形式</p>
     *
     * @param fileUrl 文件 URL
     * @return 对象名称，无法解析时返回 null
     */
    private String extractObjectName(String fileUrl) {
        if (fileUrl == null) {
            return null;
        }
        String objectName = extractObjectName(fileUrl, endpoint);
        if (objectName == null && !externalEndpoint.equals(endpoint)) {
            objectName = extractObjectName(fileUrl, externalEndpoint);
        }
        return objectName;
    }

    private String extractObjectName(String fileUrl, String endpointPrefix) {
        String prefix = endpointPrefix + "/" + bucketName + "/";
        if (fileUrl.startsWith(prefix)) {
            return fileUrl.substring(prefix.length());
        }
        return null;
    }

    /**
     * 从 MinIO 下载文件
     * <p>根据文件 URL 解析出对象名称后下载，返回文件输入流</p>
     *
     * @param fileUrl 文件 URL（由本服务生成的完整 URL）
     * @return 文件输入流
     * @throws RuntimeException 下载失败时抛出
     */
    public InputStream downloadFile(String fileUrl) {
        try {
            String objectName = extractObjectName(fileUrl);
            if (objectName == null) {
                throw new RuntimeException("无法解析文件URL: " + fileUrl);
            }
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build()
            );
        } catch (Exception e) {
            log.error("文件下载失败: {}", fileUrl, e);
            throw new RuntimeException("文件下载失败: " + e.getMessage(), e);
        }
    }
}
