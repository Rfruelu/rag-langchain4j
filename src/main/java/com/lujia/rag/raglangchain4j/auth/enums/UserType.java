package com.lujia.rag.raglangchain4j.auth.enums;

/**
 * 用户类型枚举
 * <p>权限等级：VISITOR(1) < CUSTOMER(2) < STAFF(3)</p>
 */
public enum UserType {

    /**
     * 访客 - 最低权限，可访问公开文档
     */
    VISITOR("VISITOR", 1, "访客"),

    /**
     * 外部客户 - 中等权限，可访问访客文档 + 客户文档
     */
    CUSTOMER("CUSTOMER", 2, "外部客户"),

    /**
     * 客服（内部员工）- 最高权限，可访问所有文档
     */
    STAFF("STAFF", 3, "客服");

    private final String code;
    private final int level;
    private final String description;

    UserType(String code, int level, String description) {
        this.code = code;
        this.level = level;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public int getLevel() {
        return level;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 根据 code 获取枚举
     */
    public static UserType fromCode(String code) {
        if (code == null) return VISITOR;
        for (UserType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return VISITOR;
    }

    /**
     * 判断当前用户类型是否可以访问指定权限级别的文档
     * <p>用户权限等级 >= 文档权限等级时可以访问</p>
     *
     * @param documentAccessibleBy 文档的访问权限级别
     * @return true 表示可以访问
     */
    public boolean canAccess(String documentAccessibleBy) {
        if (documentAccessibleBy == null || documentAccessibleBy.isBlank()) {
            // 历史文档无权限信息，默认仅客服可访问
            return this.level >= STAFF.level;
        }
        UserType docType = fromCode(documentAccessibleBy);
        return this.level >= docType.level;
    }
}
