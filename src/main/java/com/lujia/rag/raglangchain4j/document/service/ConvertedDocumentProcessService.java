package com.lujia.rag.raglangchain4j.document.service;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lujia.rag.raglangchain4j.document.constant.DocumentConstant;
import com.lujia.rag.raglangchain4j.document.dto.DocumentSplitParam;
import com.lujia.rag.raglangchain4j.document.entity.KnowledgeDocumentSegment;
import com.lujia.rag.raglangchain4j.document.entity.RagKnowledgeDocument;
import com.lujia.rag.raglangchain4j.document.enums.SplitType;
import com.lujia.rag.raglangchain4j.document.lock.DistributeLock;
import com.lujia.rag.raglangchain4j.document.splitter.DocumentSplitterFactory;
import com.lujia.rag.raglangchain4j.document.splitter.ExcelSplitter;
import com.lujia.rag.raglangchain4j.document.util.FileType;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 已转换文档后处理服务
 * <p>处理流程：</p>
 * <ol>
 *   <li>下载 MinerU 解析结果 zip 文件并解压</li>
 *   <li>将图片上传到 MinIO</li>
 *   <li>调用百炼大模型识别图片描述</li>
 *   <li>替换 Markdown 中的图片 URL 和描述</li>
 *   <li>上传处理后的 Markdown 到 MinIO</li>
 *   <li>调用 DocumentSplitterFactory 进行文档切分</li>
 *   <li>保存切分结果到 KnowledgeDocumentSegment</li>
 * </ol>
 */
@Slf4j
@Service
public class ConvertedDocumentProcessService {

    /**
     * Markdown 图片正则：匹配 ![alt](path) 或 ![alt](path "title")
     */
    private static final Pattern IMAGE_PATTERN = Pattern.compile("!\\[([^]]*)]\\(([^)\\s]+)(?:\\s+\"([^\"]*)\")?\\)");

    /**
     * 图片扩展名与 MIME 类型映射
     */
    private static final Map<String, String> MIME_TYPES = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp",
            "svg", "image/svg+xml"
    );

    /**
     * 默认切分大小（字符数），文档未设置 chunkSize 时使用
     */
    private static final int DEFAULT_CHUNK_SIZE = 500;

    /**
     * 默认分片重叠大小（字符数），文档未设置 overlap 时使用
     */
    private static final int DEFAULT_OVERLAP = 50;

    @Autowired
    private MinioService minioService;

    @Autowired
    private BailianImageService bailianImageService;

    @Autowired
    private KnowledgeDocumentSegmentService segmentService;

    @Autowired
    private RagKnowledgeDocumentService documentService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 处理单个 CONVERTED 状态的文档
     * <p>根据是否经过 MinerU 处理走不同流程：</p>
     * <ul>
     *   <li>经过 MinerU（PDF）：解压 zip → 图片上传 → AI识别 → MD替换 → 上传MD → 切分 → 保存</li>
     *   <li>未经 MinerU（非 PDF）：直接下载文件 → 读取内容 → 切分 → 保存</li>
     * </ul>
     *
     * @param document 待处理的文档记录
     */
    @DistributeLock(scene = "document_process", key = "#document.id")
    public void processDocument(RagKnowledgeDocument document) {
        Long docId = document.getId();
        String parseResultUrl = document.getParseResultUrl();
        log.info("开始处理文档 {}: parseResultUrl={}, mineruTaskId={}", docId, parseResultUrl, document.getMineruTaskId());

        try {
            List<TextSegment> segments;
            FileType fileType = FileType.getFileType(document.getFileType());

            //EXCEL单独处理，因为他是二进制格式，不能经过String转换
            if (FileType.EXCEL.equals(fileType)) {
                byte[] fileBytes = downloadRawBytes(document.getFileUrl());
                document.setProcessedMdUrl(document.getFileUrl());
                ExcelSplitter splitter = new ExcelSplitter(document.getChunkSize(), false);
                segments = splitter.split(fileBytes);
            } else if (FileType.CSV.equals(fileType)) {
                // CSV是文本格式，可以经过String转换
                String content;
                if (document.getMineruTaskId() != null && !document.getMineruTaskId().isEmpty()) {
                    content = processMineruResult(document);
                } else {
                    content = processDirectFile(document);
                }
                ExcelSplitter splitter = new ExcelSplitter(document.getChunkSize(), false);
                segments = splitter.split(content.getBytes(StandardCharsets.UTF_8));
            } else {
                // 其他文件类型：走正常文本处理流程
                String content;
                if (document.getMineruTaskId() != null && !document.getMineruTaskId().isEmpty()) {
                    content = processMineruResult(document);
                } else {
                    content = processDirectFile(document);
                }
                segments = splitDocument(content, document);
            }

            // 文档切分（使用文档自身的 chunkSize 和 overlap 参数）
            log.info("文档 {} 切分完成，共 {} 个分片", docId, segments.size());

            // 保存分片到数据库
            saveSegments(docId, segments, document);

            // 更新文档信息（状态由事件监听器更新）
            documentService.updateById(document);
            log.info("文档 {} 处理完成", docId);

        } catch (Exception e) {
            log.error("文档 {} 处理失败", docId, e);
            throw new RuntimeException("文档处理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 处理经过 MinerU 解析的文档
     * <p>下载 zip 文件 → 解压 → 图片上传 MinIO → AI识别图片描述 → 替换 MD 中图片引用 → 上传处理后 MD</p>
     *
     * @param document 文档记录
     * @return 处理后的 Markdown 内容
     */
    private String processMineruResult(RagKnowledgeDocument document) {
        Long docId = document.getId();
        String parseResultUrl = document.getParseResultUrl();
        log.info("处理 MinerU 解析结果: docId={}, zipUrl={}", docId, parseResultUrl);

        // 1. 下载并解压 zip 文件
        Map<String, byte[]> zipContents = downloadAndExtractZip(parseResultUrl);

        // 分离 MD 文件和图片文件
        String mdContent = null;
        String mdFileName = null;
        Map<String, byte[]> imageFiles = new LinkedHashMap<>();

        for (Map.Entry<String, byte[]> entry : zipContents.entrySet()) {
            String entryName = entry.getKey();
            byte[] data = entry.getValue();

            if (entryName.endsWith(".md")) {
                mdContent = new String(data, StandardCharsets.UTF_8);
                mdFileName = entryName;
            } else if (isImageFile(entryName)) {
                imageFiles.put(entryName, data);
            }
        }

        if (mdContent == null) {
            log.error("文档 {} 的 zip 中未找到 Markdown 文件", docId);
            throw new RuntimeException("zip 中未找到 Markdown 文件");
        }

        log.info("文档 {} 解压完成: MD文件={}, 图片数量={}", docId, mdFileName, imageFiles.size());

        // 2. 处理图片：上传到 MinIO 并调用百炼识别描述
        Map<String, ImageInfo> imageMapping = new LinkedHashMap<>();
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));

        for (Map.Entry<String, byte[]> entry : imageFiles.entrySet()) {
            String imagePath = entry.getKey();
            byte[] imageData = entry.getValue();
            String mimeType = getMimeType(imagePath);

            // 上传图片到 MinIO
            String imageObjectName = datePath + "/doc_" + docId + "/" + Path.of(imagePath).getFileName();
            String imageUrl = minioService.uploadFile(
                    new ByteArrayInputStream(imageData), imageObjectName, mimeType);
            log.debug("文档 {} 图片上传成功: {} -> {}", docId, imagePath, imageUrl);

            // 调用百炼识别图片描述
            String description;
            try {
                description = bailianImageService.describeImage(imageData, mimeType);
                log.debug("文档 {} 图片识别完成: {} -> {}", docId, imagePath, description);
            } catch (Exception e) {
                log.warn("文档 {} 图片 {} 识别失败，使用文件名作为描述", docId, imagePath, e);
                description = Path.of(imagePath).getFileName().toString();
            }

            imageMapping.put(imagePath, new ImageInfo(imageUrl, description));
        }

        // 3. 替换 Markdown 中的图片引用
        String processedMd = replaceImagesInMarkdown(mdContent, imageMapping);

        // 4. 上传处理后的 MD 文件到 MinIO
        String mdObjectName = datePath + "/doc_" + docId + "/processed.md";
        String processedMdUrl = minioService.uploadFile(
                new ByteArrayInputStream(processedMd.getBytes(StandardCharsets.UTF_8)),
                mdObjectName, "text/markdown; charset=utf-8");
        log.info("文档 {} 处理后的 MD 上传成功: {}", docId, processedMdUrl);

        document.setProcessedMdUrl(processedMdUrl);
        return processedMd;
    }

    /**
     * 处理未经 MinerU 解析的文档（txt、md 等纯文本文件）
     * <p>直接从 MinIO 下载文件并读取文本内容</p>
     *
     * @param document 文档记录
     * @return 文件文本内容
     */
    private String processDirectFile(RagKnowledgeDocument document) {
        Long docId = document.getId();
        String fileUrl = document.getFileUrl();
        log.info("直接处理文档文件: docId={}, fileUrl={}", docId, fileUrl);

        try (InputStream is = minioService.downloadFile(fileUrl)) {

            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            log.info("文档 {} 直接读取完成，内容长度: {} 字符", docId, content.length());

            // 将原始文件地址作为处理后的地址
            document.setProcessedMdUrl(fileUrl);

            return content;
        } catch (Exception e) {
            throw new RuntimeException("下载或读取文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 下载并解压 zip 文件
     *
     * @param zipUrl zip 文件的 MinIO 地址
     * @return 文件名到文件内容的映射
     */
    private Map<String, byte[]> downloadAndExtractZip(String zipUrl) {
        Map<String, byte[]> contents = new LinkedHashMap<>();

        try (InputStream is = minioService.downloadFile(zipUrl);
             ZipInputStream zis = new ZipInputStream(is, StandardCharsets.UTF_8)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                // 跳过 macOS 资源文件和隐藏文件
                if (name.startsWith("__MACOSX") || name.contains("/.")) {
                    continue;
                }
                contents.put(name, zis.readAllBytes());
            }
        } catch (Exception e) {
            throw new RuntimeException("下载或解压 zip 文件失败: " + e.getMessage(), e);
        }

        return contents;
    }

    /**
     * 替换 Markdown 中的图片引用
     * <p>将 ![alt](original_path) 替换为 ![description](minio_url)</p>
     *
     * @param markdown     原始 Markdown 内容
     * @param imageMapping 图片路径到 ImageInfo 的映射
     * @return 替换后的 Markdown 内容
     */
    private String replaceImagesInMarkdown(String markdown, Map<String, ImageInfo> imageMapping) {
        if (imageMapping.isEmpty()) {
            return markdown;
        }

        Matcher matcher = IMAGE_PATTERN.matcher(markdown);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String originalAlt = matcher.group(1);
            String originalPath = matcher.group(2);

            // 尝试匹配图片路径（支持相对路径和文件名匹配）
            ImageInfo info = findImageInfo(originalPath, imageMapping);

            if (info != null) {
                // 替换为百炼识别的描述和 MinIO URL
                String newAlt = info.description;
                String newUrl = info.minioUrl;
                matcher.appendReplacement(result, "![" + Matcher.quoteReplacement(newAlt) + "](" + Matcher.quoteReplacement(newUrl) + ")");
            } else {
                // 未找到匹配的图片，保留原样
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * 根据图片路径查找对应的 ImageInfo
     * <p>支持精确匹配和文件名匹配</p>
     */
    private ImageInfo findImageInfo(String path, Map<String, ImageInfo> imageMapping) {
        // 精确匹配
        if (imageMapping.containsKey(path)) {
            return imageMapping.get(path);
        }

        // 文件名匹配（处理路径前缀不同的情况）
        String fileName = Path.of(path).getFileName().toString();
        for (Map.Entry<String, ImageInfo> entry : imageMapping.entrySet()) {
            if (Path.of(entry.getKey()).getFileName().toString().equals(fileName)) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * 使用 DocumentSplitterFactory 进行文档切分
     * <p>从文档记录中读取 chunkSize 和 overlap 参数，若未设置则使用默认值</p>
     *
     * @param markdownContent 处理后的 Markdown 内容
     * @param document        文档记录，包含分片参数
     * @return 切分后的文本片段列表
     */
    private List<TextSegment> splitDocument(String markdownContent, RagKnowledgeDocument document) {
        int chunkSize = document.getChunkSize() != null ? document.getChunkSize() : DEFAULT_CHUNK_SIZE;
        int overlap = document.getOverlap() != null ? document.getOverlap() : DEFAULT_OVERLAP;

        // 使用 SMART 模式智能切分
        DocumentSplitParam splitParam = new DocumentSplitParam(
                SplitType.SMART.name(),
                chunkSize,
                overlap,
                null,   // titleLevel
                null,   // separator
                null    // regex
        );

        DocumentSplitter splitter = DocumentSplitterFactory.getInstance(splitParam);
        if (splitter == null) {
            throw new RuntimeException("无法创建文档分割器，splitType: " + splitParam.splitType());
        }

        return splitter.split(Document.from(markdownContent));
    }

    /**
     * 保存切分后的文档片段到数据库
     *
     * @param docId    文档ID
     * @param segments 切分后的文本片段列表
     */
    private void saveSegments(Long docId, List<TextSegment> segments, RagKnowledgeDocument document) {
        List<KnowledgeDocumentSegment> segmentEntities = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            KnowledgeDocumentSegment entity = new KnowledgeDocumentSegment();
            entity.setDocumentId(docId);
            entity.setText(segment.text());
            entity.setChunkId(IdUtil.getSnowflakeNextIdStr());
            entity.setChunkOrder(i + 1);
            entity.setStatus("STORED");

            // 将元数据序列化为 JSON
            Map<String, Object> metadataMap = new HashMap<>(segment.metadata().toMap());
            // 补充文档级元数据
            metadataMap.put(DocumentConstant.DOC_ID, String.valueOf(docId));
            metadataMap.put(DocumentConstant.DOCUMENT_TITLE, document.getTitle());
            if (document.getFileUrl() != null) {
                metadataMap.put(DocumentConstant.DOC_FILE_URL, document.getFileUrl());
            }
            try {
                entity.setMetadata(objectMapper.writeValueAsString(metadataMap));
            } catch (Exception e) {
                log.warn("文档 {} 第 {} 个分片元数据序列化失败", docId, i, e);
                entity.setMetadata("{}");
            }

            // 检查是否需要跳过 embedding
            Object skipEmbedding = segment.metadata().toMap().get(DocumentConstant.SKIP_EMBEDDING);
            if (skipEmbedding != null && Integer.valueOf(1).equals(skipEmbedding)) {
                entity.setSkipEmbedding(1);
            } else {
                entity.setSkipEmbedding(0);
            }

            segmentEntities.add(entity);
        }

        // 批量保存
        segmentService.saveBatch(segmentEntities);
        log.info("文档 {} 保存 {} 个分片成功", docId, segmentEntities.size());
    }

    /**
     * 从 MinIO 下载文件并返回原始字节数组
     * <p>用于二进制格式文件（如 Excel），避免经过 String 转换导致数据损坏</p>
     *
     * @param fileUrl 文件 MinIO 地址
     * @return 文件原始字节数据
     */
    private byte[] downloadRawBytes(String fileUrl) {
        try (InputStream is = minioService.downloadFile(fileUrl)) {
            return is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("下载文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 判断文件是否为图片
     */
    private boolean isImageFile(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".svg");
    }

    /**
     * 根据文件扩展名获取 MIME 类型
     */
    private String getMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        int dotIndex = lower.lastIndexOf('.');
        if (dotIndex >= 0) {
            String ext = lower.substring(dotIndex + 1);
            return MIME_TYPES.getOrDefault(ext, "application/octet-stream");
        }
        return "application/octet-stream";
    }

    /**
     * 图片处理结果
     */
    private record ImageInfo(String minioUrl, String description) {
    }
}
