package com.lujia.rag.raglangchain4j.document.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

@Slf4j
public class FileTypeUtil {

    private static final Tika tika = new Tika();

    public static FileType getFileType(String fileName, MultipartFile file) {
        if (file == null) {
            return null;
        }

        return resolveFileType(fileName, detectMimeType(file));
    }

    public static FileType getFileType(String fileName) {
        if (fileName == null) {
            return null;
        }

        return resolveFileType(fileName, null);
    }

    /**
     * 使用 Apache Tika 检测文件内容类型，整个检测过程只打开一次输入流
     *
     * @param file 上传的文件
     * @return 检测到的 MIME 类型，检测失败时返回 null
     */
    private static String detectMimeType(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            return tika.detect(is);
        } catch (IOException e) {
            log.error("文件类型检测失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 根据文件名后缀和 MIME 类型（可能为 null）综合判断文件类型
     * <p>纯文本内容（text/plain）的 Markdown 与 txt 无法通过内容区分，Markdown 仅按 .md 后缀识别</p>
     *
     * @param fileName 文件名
     * @param mimeType Tika 检测到的 MIME 类型，未检测时为 null
     * @return 文件类型，无法识别时返回 null
     */
    private static FileType resolveFileType(String fileName, String mimeType) {
        if (isPdfFile(fileName) || "application/pdf".equals(mimeType)) {
            return FileType.PDF;
        }
        if (isCsvFile(fileName) || "text/csv".equals(mimeType)) {
            return FileType.CSV;
        }
        if (isExcelFile(fileName) || isExcelMimeType(mimeType)) {
            return FileType.EXCEL;
        }
        if (isDocFile(fileName) || isDocMimeType(mimeType)) {
            return FileType.DOC;
        }
        if (isMarkdownFile(fileName)) {
            return FileType.MARKDOWN;
        }
        if (isTxtFile(fileName) || "text/plain".equals(mimeType) || "application/txt".equals(mimeType)) {
            return FileType.TXT;
        }
        return null;
    }

    private static boolean isExcelMimeType(String mimeType) {
        return "application/vnd.ms-excel".equals(mimeType) ||
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(mimeType);
    }

    private static boolean isDocMimeType(String mimeType) {
        return "application/msword".equals(mimeType) ||
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mimeType);
    }


    /**
     * 通过后缀名判断是否为 PDF 文件
     */
    private static boolean isPdfFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        return fileName.toLowerCase().endsWith(".pdf");
    }

    /**
     * 通过后缀名判断是否为 Excel 文件
     */
    private static boolean isExcelFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        return fileName.toLowerCase().endsWith(".xlsx") || fileName.toLowerCase().endsWith(".xls");
    }

    /**
     * 通过后缀名判断是否为 Word 文件
     *
     * @param fileName
     * @return
     */
    private static boolean isDocFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        return fileName.toLowerCase().endsWith(".docx") || fileName.toLowerCase().endsWith(".doc");
    }

    /**
     * 通过后缀名判断是否为 markdown 文件
     *
     * @param fileName
     * @return
     */
    private static boolean isMarkdownFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        return fileName.toLowerCase().endsWith(".md");
    }

    /**
     * 通过后缀名判断是否为 txt 文件
     *
     * @param fileName
     * @return
     */
    private static boolean isTxtFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        return fileName.toLowerCase().endsWith(".txt");
    }

    /**
     * 通过后缀名判断是否为 CSV 文件
     */
    private static boolean isCsvFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        return fileName.toLowerCase().endsWith(".csv");
    }

}
