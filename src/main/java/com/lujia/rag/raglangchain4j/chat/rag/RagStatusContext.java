package com.lujia.rag.raglangchain4j.chat.rag;

import java.util.function.Consumer;

/**
 * RAG 管道状态上下文
 * <p>使用 ThreadLocal 在 RAG 管道组件之间传递状态回调，
 * 使各组件（QueryTransformer、QueryRouter、ContentRetriever）能够向前端推送实时处理进度</p>
 *
 * <p>使用方式：</p>
 * <ol>
 *   <li>在调用 streamChat 前通过 {@link #set(Consumer)} 设置回调</li>
 *   <li>RAG 管道组件通过 {@link #emitStatus(int, int, String)} 发射状态事件</li>
 *   <li>流式完成后通过 {@link #clear()} 清理 ThreadLocal</li>
 * </ol>
 */
public class RagStatusContext {

    private static final ThreadLocal<Consumer<String>> STATUS_CALLBACK = new ThreadLocal<>();

    /**
     * 设置当前线程的状态回调
     *
     * @param callback 接收状态 JSON 字符串的回调
     */
    public static void set(Consumer<String> callback) {
        STATUS_CALLBACK.set(callback);
    }

    /**
     * 获取当前线程的状态回调
     *
     * @return 状态回调，未设置时返回 null
     */
    public static Consumer<String> get() {
        return STATUS_CALLBACK.get();
    }

    /**
     * 清除当前线程的状态回调
     */
    public static void clear() {
        STATUS_CALLBACK.remove();
    }

    /**
     * 发射 RAG 管道状态事件
     * <p>格式：{@code [RAG_STATUS]{"step":1,"totalSteps":4,"progress":25,"title":"正在改写查询","description":"..."}}</p>
     *
     * @param step       当前步骤编号（从1开始）
     * @param totalSteps 总步骤数
     * @param title      步骤标题
     */
    public static void emitStatus(int step, int totalSteps, String title) {
        emitStatus(step, totalSteps, title, null);
    }

    /**
     * 发射 RAG 管道状态事件（带描述）
     *
     * @param step        当前步骤编号（从1开始）
     * @param totalSteps  总步骤数
     * @param title       步骤标题
     * @param description 步骤描述（可选）
     */
    public static void emitStatus(int step, int totalSteps, String title, String description) {
        Consumer<String> callback = get();
        if (callback != null) {
            int progress = (int) Math.round((double) step / totalSteps * 100);
            StringBuilder json = new StringBuilder();
            json.append("[RAG_STATUS]{\"step\":").append(step)
                    .append(",\"totalSteps\":").append(totalSteps)
                    .append(",\"progress\":").append(progress)
                    .append(",\"title\":\"").append(JsonEscapeUtil.escapeJson(title)).append("\"");
            if (description != null && !description.isEmpty()) {
                json.append(",\"description\":\"").append(JsonEscapeUtil.escapeJson(description)).append("\"");
            }
            json.append("}");
            callback.accept(json.toString());
        }
    }

    private RagStatusContext() {
    }
}
