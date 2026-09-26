package com.lujia.rag.raglangchain4j.document.splitter;


import cn.hutool.core.util.IdUtil;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

import static com.lujia.rag.raglangchain4j.document.constant.DocumentConstant.*;


/**
 * Markdown标题分割器抽象基类，基于标题层级进行文档分段
 * 支持保留元数据等高级特性，子类通过实现 splitByChunkSize 定义不同的分片切割语义
 *
 * @author andyflury （https://github.com/langchain4j/langchain4j/issues/574 ）
 */
@Slf4j
public abstract class AbstractMarkdownHeaderTextSplitter implements DocumentSplitter {

    protected static final Map<String, String> DEFAULT_HEADERS_TO_SPLIT = new HashMap<>();

    static {
        DEFAULT_HEADERS_TO_SPLIT.put("#", "title");
        DEFAULT_HEADERS_TO_SPLIT.put("##", "subtitle");
        DEFAULT_HEADERS_TO_SPLIT.put("###", "subsubtitle");
        DEFAULT_HEADERS_TO_SPLIT.put("####", "subsubsubtitle");
        DEFAULT_HEADERS_TO_SPLIT.put("#####", "subsubsubsubtitle");
        DEFAULT_HEADERS_TO_SPLIT.put("######", "subsubsubsubsubtitle");
    }

    /**
     * 需要分割的标题列表，按标题标记长度倒序排列
     */
    protected List<Map.Entry<String, String>> headersToSplitOn;

    /**
     * 是否按行返回结果
     */
    protected boolean returnEachLine;

    /**
     * 是否剥离标题行本身
     */
    protected boolean stripHeaders;

    /**
     * 每个分片的最大字符数，0表示不限制
     */
    protected int chunkSize;

    /**
     * 相邻分片之间的重叠字符数
     */
    protected int overlap;

    /**
     * 构造函数（支持 chunkSize 和 overlap）
     *
     * @param headersToSplitOn 标题分割映射表，key为标题标记（如"#"、"##"），value为元数据中的键名
     * @param returnEachLine   是否按行返回结果，false时会聚合相同元数据的行
     * @param stripHeaders     是否在结果中移除标题行
     * @param chunkSize        每个分片的最大字符数，超出则按chunkSize再次切割，0表示不限制
     * @param overlap          相邻分片之间的重叠字符数
     */
    protected AbstractMarkdownHeaderTextSplitter(Map<String, String> headersToSplitOn, boolean returnEachLine, boolean stripHeaders, int chunkSize, int overlap) {
        // 按标题标记长度倒序排列，确保优先匹配更长的标记（如"###"优先于"##"）
        this.headersToSplitOn = headersToSplitOn.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> -e.getKey().length()))
                .collect(Collectors.toList());
        this.returnEachLine = returnEachLine;
        this.stripHeaders = stripHeaders;
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    public List<TextSegment> split(Document document) {
        log.debug("开始解析Markdown文档...");
        // 移除文档中所有空行
        String text = Arrays.stream(document.text().split("\n"))
                .filter(line -> !line.trim().isEmpty())
                .collect(Collectors.joining("\n"));

        List<TextSegment> result = new ArrayList<>();
        List<DocumentWithMetadata> segments = splitWithMetadata(text, document.metadata().toMap());
        for (DocumentWithMetadata segment : segments) {
            result.add(new TextSegment(segment.getContent(), Metadata.from(segment.getMetadata())));
        }

        return result;
    }

    /**
     * 简化版分割方法，不保留元数据
     *
     * @param text 待分割的文本
     * @return 分割后的文本片段列表
     */

    public List<TextSegment> splitText(String text) {
        // 移除文本中所有空行
        String filteredText = Arrays.stream(text.split("\n"))
                .filter(line -> !line.trim().isEmpty())
                .collect(Collectors.joining("\n"));

        List<TextSegment> result = new ArrayList<>();
        List<DocumentWithMetadata> segments = splitWithMetadata(filteredText, new HashMap<>());
        for (DocumentWithMetadata segment : segments) {
            result.add(new TextSegment(segment.getContent(), Metadata.from(segment.getMetadata())));
        }

        return result;
    }

    /**
     * 核心分割逻辑，保留元数据
     *
     * @param text         待分割的文本
     * @param baseMetadata 基础元数据，会被传递到每个分段中
     * @return 带有元数据的文档片段列表
     */
    private List<DocumentWithMetadata> splitWithMetadata(String text, Map<String, Object> baseMetadata) {
        List<String> lines = Arrays.asList(text.split("\n"));
        List<Line> linesWithMetadata = new ArrayList<>();
        List<String> currentContent = new ArrayList<>();
        Map<String, Object> currentMetadata = new HashMap<>(baseMetadata);
        List<Header> headerStack = new ArrayList<>();  // 标题栈，用于追踪当前的标题层级结构
        Map<String, Object> initialMetadata = new HashMap<>(baseMetadata);

        boolean inCodeBlock = false;  // 是否在代码块中
        String openingFence = "";     // 代码块的开始标记

        for (String line : lines) {
            String strippedLine = line.trim();

            // 处理代码块标记，代码块内的内容不作为标题处理
            if (!inCodeBlock) {
                if (strippedLine.startsWith("```")) {
                    inCodeBlock = !inCodeBlock;
                    openingFence = "```";
                } else if (strippedLine.startsWith("~~~")) {
                    inCodeBlock = !inCodeBlock;
                    openingFence = "~~~";
                }
            } else {
                if (strippedLine.startsWith(openingFence)) {
                    inCodeBlock = false;
                    openingFence = "";
                }
            }

            // 代码块内的内容直接添加，不做标题检测
            if (inCodeBlock) {
                currentContent.add(strippedLine);
                continue;
            }

            // 检测并处理标题行，非标题行则按普通内容处理
            if (!processHeaderLine(strippedLine, currentContent, headerStack, initialMetadata, linesWithMetadata, currentMetadata)) {
                if (!strippedLine.isEmpty()) {
                    currentContent.add(strippedLine);
                } else if (!currentContent.isEmpty()) {
                    // 遇到空行时，保存当前累积的内容
                    linesWithMetadata.add(new Line(String.join("\n", currentContent), currentMetadata));
                    currentContent.clear();
                }
            }

            // 更新当前元数据为最新的标题信息
            currentMetadata = new HashMap<>(initialMetadata);
        }

        // 处理最后累积的内容
        if (!currentContent.isEmpty()) {
            linesWithMetadata.add(new Line(String.join("\n", currentContent), currentMetadata));
        }

        // 根据配置决定返回方式
        List<DocumentWithMetadata> segments;
        if (!returnEachLine) {
            // 聚合模式：将相同元数据的行合并
            segments = aggregateLinesToChunks(linesWithMetadata);
        } else {
            // 逐行模式：保持每行独立
            segments = linesWithMetadata.stream()
                    .map(line -> new DocumentWithMetadata(line.getContent(), line.getMetadata()))
                    .collect(Collectors.toList());
        }

        // 如果设置了 chunkSize，对超出大小的分片进行二次切割
        if (chunkSize > 0) {
            segments = splitByChunkSize(segments);
        }

        return segments;
    }

    /**
     * 检测当前行是否为标题行，是则更新标题栈和元数据并保存之前累积的内容
     *
     * @return true 表示当前行是标题行并已处理，false 表示不是标题行
     */
    private boolean processHeaderLine(String strippedLine, List<String> currentContent,
                                      List<Header> headerStack, Map<String, Object> initialMetadata,
                                      List<Line> linesWithMetadata, Map<String, Object> currentMetadata) {
        for (Map.Entry<String, String> header : headersToSplitOn) {
            String sep = header.getKey();    // 标题标记，如"#"、"##"
            String name = header.getValue(); // 元数据中的键名

            // 判断是否为有效的标题行
            if (strippedLine.startsWith(sep) && (strippedLine.length() == sep.length() || strippedLine.charAt(sep.length()) == ' ')) {
                if (name != null) {
                    // 计算当前标题级别（统计#的个数）
                    int currentHeaderLevel = (int) sep.chars().filter(ch -> ch == '#').count();

                    // 维护标题栈：移除所有级别大于等于当前级别的标题
                    // 这样可以正确处理标题层级关系，如从### 回退到 ##
                    while (!headerStack.isEmpty() && headerStack.get(headerStack.size() - 1).getLevel() >= currentHeaderLevel) {
                        Header poppedHeader = headerStack.remove(headerStack.size() - 1);
                        initialMetadata.remove(poppedHeader.getName());
                    }

                    // 将当前标题加入栈，并更新元数据
                    Header headerType = new Header(currentHeaderLevel, name, strippedLine.substring(sep.length()).trim());
                    headerStack.add(headerType);
                    initialMetadata.put(name, headerType.getData());
                    initialMetadata.put(HEADER_LEVEL, currentHeaderLevel);
                    // 为每个分段生成唯一ID，用于后续建立父子关系
                    String currentChunkId = IdUtil.getSnowflakeNextIdStr();
                    initialMetadata.put(CHUNK_ID, currentChunkId);
                }

                // 遇到新标题时，保存之前累积的内容
                if (!currentContent.isEmpty()) {
                    linesWithMetadata.add(new Line(String.join("\n", currentContent), currentMetadata));
                    currentContent.clear();
                }

                // 根据stripHeaders配置决定是否保留标题行
                if (!stripHeaders) {
                    currentContent.add(strippedLine);
                }

                return true;
            }
        }
        return false;
    }

    /**
     * 聚合行为分块
     * 将具有相同元数据的行合并为一个分块
     *
     * @param lines 待聚合的行列表
     * @return 聚合后的文档片段列表
     */
    protected List<DocumentWithMetadata> aggregateLinesToChunks(List<Line> lines) {
        List<Line> aggregatedChunks = new ArrayList<>();
        for (Line line : lines) {
            // 情况1：元数据相同，直接合并到上一个分块
            if (!aggregatedChunks.isEmpty() && aggregatedChunks.get(aggregatedChunks.size() - 1).getMetadata().equals(line.getMetadata())) {
                Line last = aggregatedChunks.get(aggregatedChunks.size() - 1);
                last.setContent(last.getContent() + "  \n" + line.getContent());
            }
            // 情况2：元数据不同但上一行以标题结尾且未剥离标题，则也合并
            // 这样可以将标题和其下的第一段内容合并在一起
            else if (!aggregatedChunks.isEmpty() && !aggregatedChunks.get(aggregatedChunks.size() - 1).getMetadata().equals(line.getMetadata())
                    && aggregatedChunks.get(aggregatedChunks.size() - 1).getMetadata().size() < line.getMetadata().size()
                    && aggregatedChunks.get(aggregatedChunks.size() - 1).getContent().split("\n")[aggregatedChunks.get(aggregatedChunks.size() - 1).getContent().split("\n").length - 1].startsWith("#") && !stripHeaders) {

                Line last = aggregatedChunks.get(aggregatedChunks.size() - 1);
                last.setContent(last.getContent() + "  \n" + line.getContent());
            }
            // 情况3：创建新分块
            else {
                aggregatedChunks.add(line);
            }
        }

        enrichAggregatedChunks(aggregatedChunks);

        return aggregatedChunks.stream()
                .map(chunk -> new DocumentWithMetadata(chunk.getContent(), chunk.getMetadata()))
                .collect(Collectors.toList());
    }

    /**
     * 聚合后的分块增强处理钩子，子类可覆写以追加额外的元数据处理（如父子分段关系）
     *
     * @param aggregatedChunks 聚合后的分块列表
     */
    protected void enrichAggregatedChunks(List<Line> aggregatedChunks) {
    }

    /**
     * 对超出 chunkSize 的分片进行二次切割，具体切割语义由子类实现
     *
     * @param segments 原始分片列表
     * @return 切割后的分片列表
     */
    protected abstract List<DocumentWithMetadata> splitByChunkSize(List<DocumentWithMetadata> segments);


    /**
     * 内部类：表示带有元数据的文本行
     */
    public static class Line {
        /**
         * 文本内容
         */
        private String content;
        /**
         * 元数据信息
         */
        private Map<String, Object> metadata;

        public Line(String content, Map<String, Object> metadata) {
            this.content = content;
            this.metadata = metadata;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
        }
    }

    /**
     * 内部类：表示Markdown标题
     */
    public static class Header {
        /**
         * 标题级别（1-6）
         */
        private int level;
        /**
         * 元数据中的键名
         */
        private String name;
        /**
         * 标题文本内容（不含#标记）
         */
        private String data;

        public Header(int level, String name, String data) {
            this.level = level;
            this.name = name;
            this.data = data;
        }

        public int getLevel() {
            return level;
        }

        public void setLevel(int level) {
            this.level = level;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }
    }

    /**
     * 内部类：携带元数据的文档片段
     */
    protected static class DocumentWithMetadata {
        private final String content;
        private final Map<String, Object> metadata;

        public DocumentWithMetadata(String content, Map<String, Object> metadata) {
            this.content = content;
            this.metadata = new HashMap<>(metadata);
        }

        public String getContent() {
            return content;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }
    }
}
