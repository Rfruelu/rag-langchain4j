package com.lujia.rag.raglangchain4j.chat.enums;

/**
 * 用户意图分类枚举
 * <p>用于面料撮合交易系统的对话意图识别，将用户消息路由到对应的处理流程</p>
 */
public enum ChatIntent {

    /** 售前咨询：面料咨询、找布需求、推荐面料等 */
    PRE_SALES("售前咨询"),

    /** 售后问题：退换货、质量投诉、物流问题等 */
    POST_SALES("售后问题"),

    /** 面料价格咨询：价格查询、报价、费用等 */
    PRICE_INQUIRY("面料价格咨询"),

    /** 系统操作咨询：平台使用、功能操作、账号问题等 */
    SYSTEM_OPERATION("系统操作咨询"),

    /** 非业务相关：通用闲聊，使用通用模型回复 */
    GENERAL("通用闲聊");

    private final String description;

    ChatIntent(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
