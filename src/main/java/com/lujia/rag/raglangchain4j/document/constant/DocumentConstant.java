package com.lujia.rag.raglangchain4j.document.constant;


/**
 * 文档处理相关常量定义
 * <p>用于文档分割、元数据管理等场景中的键名常量，统一在 {@link dev.langchain4j.data.document.Metadata} 中使用</p>
 */
public class DocumentConstant {

    /** 文件名 */
    public static final String FILE_NAME = "fileName";

    /** 文档ID */
    public static final String DOC_ID = "docId";

    /** 分块ID，每个文档分片的唯一标识 */
    public static final String CHUNK_ID = "chunkId";

    /** 父块ID，用于建立父子分段关系 */
    public static final String PARENT_CHUNK_ID = "parentChunkId";

    /** 同级块ID，同一分片拆分出的多个子块共享此ID */
    public static final String BROTHER_CHUNK_ID = "brotherChunkId";

    /** 同级块索引，当前子块在同组中的序号（从1开始） */
    public static final String BROTHER_CHUNK_INDEX = "brotherChunkIndex";

    /** 同级块总数，当前子块所属同组的总分片数 */
    public static final String BROTHER_CHUNK_TOTAL = "brotherChunkTotal";

    /** 标题级别，Markdown 标题的层级（1-6） */
    public static final String HEADER_LEVEL = "headerLevel";

    /** 访问权限，控制文档的可见范围 */
    public static final String ACCESSIBLE_BY = "accessibleBy";

    /** 文件地址，文档的存储路径或URL */
    public static final String URL = "url";

    /** 文件版本，文档的版本号 */
    public static final String VERSION = "version";

    /** 分类，文档所属类别 */
    public static final String CATEGORY = "category";

    /** 摘要，文档的内容摘要 */
    public static final String SUMMARY = "summary";

    /** 关键字，文档的关键词 */
    public static final String KEYWORDS = "keywords";

    /** 跳过embedding标记，值为1表示该分片不需要进行向量化处理 */
    public static final String SKIP_EMBEDDING = "skipEmbedding";

    /** 文档标题，用于检索时展示引用文档名称 */
    public static final String DOCUMENT_TITLE = "documentTitle";

    /** 文件URL地址，用于检索时展示引用文档链接 */
    public static final String DOC_FILE_URL = "docFileUrl";
}
