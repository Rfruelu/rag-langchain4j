package com.lujia.rag.raglangchain4j.document.splitter;

import cn.hutool.core.util.IdUtil;
import cn.idev.excel.EasyExcel;
import cn.idev.excel.context.AnalysisContext;
import cn.idev.excel.read.listener.ReadListener;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static com.lujia.rag.raglangchain4j.document.constant.DocumentConstant.CHUNK_ID;


/**
 * RAGFlow风格的Excel解析器 - Java实现
 * 参考: https://github.com/infiniflow/ragflow
 * <p>
 * 功能特性:
 * 1. 支持 .xlsx 和 .xls 格式
 * 2. 支持 CSV 格式
 * 3. 双模式输出: 键值对模式 / HTML表格模式
 * 4. 智能分块: 按字符数分块大表格（同一行不会被拆分到不同分块）
 * 5. 编码自动检测 (CSV)
 * <p>
 */
@Slf4j
public class ExcelSplitter {
    /**
     * 是否使用HTML表格模式
     */
    private boolean htmlMode;

    /**
     * 默认分块字符数
     */
    public static final int DEFAULT_CHUNK_SIZE = 500;

    /**
     * 分块字符数，用于HTML表格模式
     * 表示每个分块包含的最大字符数，同一行不会被拆分到不同的分块中
     */
    private final int chunkSize;

    /**
     * 默认构造，使用默认分块大小（500字符），非HTML模式
     */
    public ExcelSplitter() {
        this(DEFAULT_CHUNK_SIZE);
    }

    /**
     * 指定分块大小，非HTML模式
     *
     * @param chunkSize 每个分块的最大字符数
     */
    public ExcelSplitter(int chunkSize) {
        this.chunkSize = chunkSize;
        this.htmlMode = false;
    }

    /**
     * 指定分块大小和输出模式
     *
     * @param chunkSize 每个分块的最大字符数
     * @param htmlMode  是否使用HTML表格模式输出
     */
    public ExcelSplitter(int chunkSize, boolean htmlMode) {
        this.chunkSize = chunkSize;
        this.htmlMode = htmlMode;
    }

    // ==================== 核心解析方法 ====================

    /**
     * 双模式解析入口
     *
     * @param fileData 文件字节数据
     * @return 分割后的文本片段列表，每个片段包含唯一ID元数据
     * @throws IOException 文件读取异常
     */
    public List<TextSegment> split(byte[] fileData) throws IOException {
        log.debug("开始解析Excel文件...");
        ExcelFileType fileType = detectFileType(fileData);
        List<String> chunks = new ArrayList<>();
        switch (fileType) {
            case XLSX:
            case XLS:
                chunks = parseExcel(fileData);
                break;
            case CSV:
                chunks = parseCsv(fileData);
                break;
            default:
                throw new IllegalArgumentException("不支持的文件格式");
        }

        return chunks.stream().map(s -> {
            Map<String, Object> metadata = new HashMap<>();
            String parentChunkId = IdUtil.getSnowflakeNextIdStr();
            metadata.put(CHUNK_ID, parentChunkId);
            return new TextSegment(s, Metadata.from(metadata));
        }).collect(Collectors.toCollection(ArrayList::new));
    }

    // ==================== Excel解析实现 ====================

    /**
     * 解析 Excel（.xlsx/.xls）文件
     * <p>使用 FastExcel 流式读取，设置 headRowNumber(0) 确保从第一行开始读取（不跳过表头）</p>
     *
     * @param fileData 文件字节数据
     * @return 处理后的分块列表
     */
    private List<String> parseExcel(byte[] fileData) throws IOException {
        List<List<String>> allRows = new ArrayList<>();

        try (ByteArrayInputStream bis = new ByteArrayInputStream(fileData)) {
            EasyExcel.read(bis, new ReadListener<Map<Integer, String>>() {
                @Override
                public void invoke(Map<Integer, String> data, AnalysisContext context) {
                    // 将Map转换为有序列表
                    List<String> row = new ArrayList<>();
                    int maxIndex = data.keySet().stream().max(Integer::compareTo).orElse(-1);
                    for (int i = 0; i <= maxIndex; i++) {
                        row.add(data.getOrDefault(i, ""));
                    }
                    allRows.add(row);
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                    // 解析完成
                }
                // EasyExcel 默认将第一行视为表头，不会通过 ReadListener.invoke() 回调返回。所以 parseExcel 返回的数据实际上是从 Excel 的第二行开始的。
                // 需要设置 headRowNumber(0) 告诉 EasyExcel 从第一行就开始读取数据
            }).headRowNumber(0).sheet().doRead();
        }

        return processRows(allRows);
    }

    // ==================== CSV解析实现 ====================

    /**
     * 解析 CSV 文件
     * <p>自动检测编码后逐行读取，支持引号包裹的字段</p>
     *
     * @param fileData 文件字节数据
     * @return 处理后的分块列表
     */
    private List<String> parseCsv(byte[] fileData) throws IOException {
        // 检测编码
        Charset charset = detectCharset(fileData);

        List<List<String>> allRows = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(fileData), charset))) {

            String line;
            while ((line = reader.readLine()) != null) {
                List<String> row = parseCsvLine(line);
                allRows.add(row);
            }
        }

        return processRows(allRows);
    }

    /**
     * 简单的CSV行解析（处理引号包裹的字段）
     */
    private List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());

        return fields;
    }

    // ==================== 数据处理核心 ====================

    /**
     * 行数据处理入口
     * <p>先清理非法字符，再根据模式选择输出方式</p>
     *
     * @param allRows 所有行数据（第一行为表头）
     * @return 分块结果列表
     */
    private List<String> processRows(List<List<String>> allRows) {
        if (allRows.isEmpty()) {
            return Collections.emptyList();
        }

        // 清理数据：移除非法控制字符
        allRows = cleanData(allRows);

        if (htmlMode) {
            return convertToHtmlChunks(allRows);
        } else {
            return convertToKeyValuePairs(allRows);
        }
    }

    /**
     * 清理非法控制字符
     */
    private List<List<String>> cleanData(List<List<String>> rows) {
        return rows.stream()
                .map(row -> row.stream()
                        .map(this::cleanCell)
                        .collect(Collectors.toList()))
                .collect(Collectors.toList());
    }

    /**
     * 清理单个单元格中的非法控制字符
     *
     * @param cell 单元格内容
     * @return 清理后的内容，null 返回空字符串
     */
    private String cleanCell(String cell) {
        if (cell == null) return "";
        // 移除控制字符 (0x00-0x1F)，保留换行符(0x0A)和制表符(0x09)
        return cell.replaceAll("[\\x00-\\x09\\x0B-\\x0C\\x0E-\\x1F]", "");
    }

    /**
     * 键值对模式转换
     * 格式: "表头1: 值1; 表头2: 值2; ..."
     */
    private List<String> convertToKeyValuePairs(List<List<String>> rows) {
        List<String> result = new ArrayList<>();

        if (rows.size() < 2) {
            return result; // 至少需要表头+一行数据
        }

        List<String> headers = rows.get(0);

        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            StringBuilder sb = new StringBuilder();

            for (int j = 0; j < headers.size() && j < row.size(); j++) {
                String header = headers.get(j).trim();
                String value = row.get(j).trim();

                if (!header.isEmpty() || !value.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append("; ");
                    }
                    sb.append(header).append("：").append(value);
                }
            }

            if (sb.length() > 0) {
                result.add(sb.toString());
            }
        }

        return result;
    }

    /**
     * HTML表格模式转换
     * 按chunkSize字符数分块输出，同一行不会被拆分到不同的分块中
     */
    private List<String> convertToHtmlChunks(List<List<String>> rows) {
        List<String> result = new ArrayList<>();

        if (rows.isEmpty()) {
            return result;
        }

        List<String> headers = rows.get(0);
        List<List<String>> dataRows = rows.subList(1, rows.size());

        // 按chunkSize字符数分块，确保同一行不被拆分
        List<List<String>> currentChunk = new ArrayList<>();
        int currentChunkSize = 0;

        // 计算表头的字符数
        int headerSize = calculateRowSize(headers);

        for (List<String> row : dataRows) {
            int rowSize = calculateRowSize(row);

            // 如果当前分块为空，直接添加当前行（即使超过chunkSize，也要保证至少有一行）
            // 如果当前分块不为空，且添加当前行后不超过chunkSize，则添加
            // 如果当前分块不为空，且添加当前行后会超过chunkSize，则先输出当前分块，再开始新分块
            if (currentChunk.isEmpty()) {
                currentChunk.add(row);
                currentChunkSize = headerSize + rowSize;
            } else if (currentChunkSize + rowSize <= chunkSize) {
                currentChunk.add(row);
                currentChunkSize += rowSize;
            } else {
                // 当前分块已满，输出当前分块
                String html = buildHtmlTable(headers, currentChunk);
                result.add(html);

                // 开始新分块
                currentChunk = new ArrayList<>();
                currentChunk.add(row);
                currentChunkSize = headerSize + rowSize;
            }
        }

        // 处理最后一个分块
        if (!currentChunk.isEmpty()) {
            String html = buildHtmlTable(headers, currentChunk);
            result.add(html);
        }

        return result;
    }

    /**
     * 计算一行的字符数（包括表格标签的字符）
     */
    private int calculateRowSize(List<String> row) {
        int size = 0;
        // 每个单元格会有 <td> 和 </td> 标签，共9个字符
        // 加上换行符等格式化字符
        for (String cell : row) {
            size += (cell != null ? cell.length() : 0) + 9;
        }
        // 加上 <tr> 和 </tr> 标签以及格式化字符
        size += 15;
        return size;
    }

    /**
     * 将数据行构建为 HTML 表格字符串
     *
     * @param headers  表头列
     * @param dataRows 数据行
     * @return HTML 表格字符串
     */
    private String buildHtmlTable(List<String> headers, List<List<String>> dataRows) {
        StringBuilder html = new StringBuilder();
        html.append("<table>\n");

        // 表头
        html.append("  <thead>\n    <tr>\n");
        for (String header : headers) {
            html.append("      <th>").append(escapeHtml(header)).append("</th>\n");
        }
        html.append("    </tr>\n  </thead>\n");

        // 表体
        html.append("  <tbody>\n");
        for (List<String> row : dataRows) {
            html.append("    <tr>\n");
            for (int i = 0; i < headers.size(); i++) {
                String value = i < row.size() ? row.get(i) : "";
                html.append("      <td>").append(escapeHtml(value)).append("</td>\n");
            }
            html.append("    </tr>\n");
        }
        html.append("  </tbody>\n");

        html.append("</table>");
        return html.toString();
    }

    /**
     * HTML 特殊字符转义
     *
     * @param text 原始文本
     * @return 转义后的安全文本
     */
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }

    // ==================== 文件类型检测 ====================

    /**
     * 支持的Excel/CSV文件类型
     */
    private enum ExcelFileType {
        /** OOXML 格式（.xlsx） */
        XLSX,
        /** BIFF 格式（.xls） */
        XLS,
        /** 逗号分隔文本（.csv） */
        CSV,
        /** 未知格式 */
        UNKNOWN
    }

    /**
     * 通过文件头魔数检测文件类型
     */
    private ExcelFileType detectFileType(byte[] data) {
        if (data.length < 4) {
            return ExcelFileType.UNKNOWN;
        }

        // ZIP头 -> xlsx (OOXML格式)
        if (data[0] == 0x50 && data[1] == 0x4B && data[2] == 0x03 && data[3] == 0x04) {
            return ExcelFileType.XLSX;
        }

        // OLE头 -> xls (BIFF格式)
        if (data[0] == (byte) 0xD0 && data[1] == (byte) 0xCF
                && data[2] == (byte) 0x11 && data[3] == (byte) 0xE0) {
            return ExcelFileType.XLS;
        }

        // 简单判断CSV：包含大量逗号或换行符
        String sample = new String(data, 0, Math.min(100, data.length), StandardCharsets.UTF_8);
        if (sample.contains(",") && (sample.contains("\n") || sample.contains("\r"))) {
            return ExcelFileType.CSV;
        }

        return ExcelFileType.UNKNOWN;
    }

    /**
     * 简单的编码检测
     */
    private Charset detectCharset(byte[] data) {
        // 简单的BOM检测
        if (data.length >= 3 && data[0] == (byte) 0xEF && data[1] == (byte) 0xBB && data[2] == (byte) 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (data.length >= 2 && data[0] == (byte) 0xFE && data[1] == (byte) 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        if (data.length >= 2 && data[0] == (byte) 0xFF && data[1] == (byte) 0xFE) {
            return StandardCharsets.UTF_16LE;
        }

        // 默认UTF-8
        return StandardCharsets.UTF_8;
    }

    // ==================== Getters ====================

    public int getChunkSize() {
        return chunkSize;
    }
}