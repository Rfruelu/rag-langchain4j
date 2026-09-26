package com.lujia.rag.raglangchain4j.document.util;

/**
 * 文件类型
 */
public enum FileType {
    PDF("pdf"),
    DOC("doc"),
    TXT("txt"),
    HTML("html"),
    MARKDOWN("markdown"),
    CSV("csv"),
    EXCEL("excel");

    private final String type;

    FileType(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    public static FileType getFileType(String type) {
        for (FileType fileType : FileType.values()) {
            if (fileType.type.equalsIgnoreCase(type)) {
                return fileType;
            }
        }
        throw new IllegalArgumentException("Invalid file type: " + type);
    }
}
