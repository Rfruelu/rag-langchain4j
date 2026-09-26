package com.lujia.rag.raglangchain4j.chat.rag;

import com.lujia.rag.raglangchain4j.chat.enums.ChatIntent;

/**
 * 意图上下文持有者
 * <p>使用 ThreadLocal 在 RAG 管道内传递意图识别结果、会话ID和用户角色：
 * {@link IntentBasedQueryRouter} 识别意图后设置，
 * {@link IntentContentInjector} 注入内容时读取</p>
 * <p>由于 DefaultRetrievalAugmentor.augment() 在同一线程内同步执行，
 * ThreadLocal 可安全传递意图，无需额外参数</p>
 */
public class IntentContextHolder {

    private static final ThreadLocal<ChatIntent> INTENT_HOLDER = new ThreadLocal<>();

    /** 会话ID，供 RAG 管道内降级调用意图识别时使用 */
    private static final ThreadLocal<String> CONVERSATION_ID_HOLDER = new ThreadLocal<>();

    /** 当前用户类型，供检索时权限过滤使用 */
    private static final ThreadLocal<String> USER_TYPE_HOLDER = new ThreadLocal<>();

    /**
     * 设置当前线程的意图
     *
     * @param intent 识别到的用户意图
     */
    public static void setIntent(ChatIntent intent) {
        INTENT_HOLDER.set(intent);
    }

    /**
     * 获取当前线程的意图
     *
     * @return 用户意图，未设置时返回 null
     */
    public static ChatIntent getIntent() {
        return INTENT_HOLDER.get();
    }

    /**
     * 设置当前线程的会话ID
     *
     * @param conversationId 会话ID
     */
    public static void setConversationId(String conversationId) {
        CONVERSATION_ID_HOLDER.set(conversationId);
    }

    /**
     * 获取当前线程的会话ID
     *
     * @return 会话ID，未设置时返回 null
     */
    public static String getConversationId() {
        return CONVERSATION_ID_HOLDER.get();
    }

    /**
     * 设置当前用户类型
     *
     * @param userType 用户类型（VISITOR/CUSTOMER/STAFF）
     */
    public static void setUserType(String userType) {
        USER_TYPE_HOLDER.set(userType);
    }

    /**
     * 获取当前用户类型
     *
     * @return 用户类型，未设置时返回 null
     */
    public static String getUserType() {
        return USER_TYPE_HOLDER.get();
    }

    /**
     * 清除当前线程的所有上下文，防止线程复用导致的数据污染
     */
    public static void clear() {
        INTENT_HOLDER.remove();
        CONVERSATION_ID_HOLDER.remove();
        USER_TYPE_HOLDER.remove();
    }

    /** 禁止外部实例化 */
    private IntentContextHolder() {
    }
}
