package com.lujia.rag.raglangchain4j.chat.service;

import com.lujia.rag.raglangchain4j.chat.entity.RagChatConversation;
import com.lujia.rag.raglangchain4j.chat.entity.RagChatMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI对话服务接口
 */
public interface RagChatService {

    /**
     * 创建新会话
     *
     * @param userId 用户ID
     * @param title  会话标题（可选，默认"新对话"）
     * @return 创建的会话记录
     */
    RagChatConversation createConversation(String userId, String title);

    /**
     * 查询用户的会话列表（按最近活跃时间倒序）
     *
     * @param userId 用户ID
     * @return 会话列表
     */
    List<RagChatConversation> listConversations(String userId);

    /**
     * 获取指定会话的消息列表（按时间正序）
     *
     * @param conversationId 会话ID
     * @return 消息列表
     */
    List<RagChatMessage> listMessages(String conversationId);

    /**
     * 发送消息并获取AI回复
     * <p>处理流程：保存用户消息 → 意图识别 → 生成回复 → 保存AI回复 → 更新会话标题</p>
     *
     * @param conversationId 会话ID
     * @param content        用户消息内容
     * @return AI回复的消息
     */
    RagChatMessage sendMessage(String conversationId, String content);

    /**
     * 发送消息并获取AI回复（支持外部指定用户类型）
     * <p>供飞书机器人等非 Sa-Token 认证入口调用，直接使用传入的 userType 进行检索权限过滤</p>
     *
     * @param conversationId 会话ID
     * @param content        用户消息内容
     * @param userType       用户类型（VISITOR/CUSTOMER/STAFF），用于检索权限过滤
     * @return AI回复的消息
     */
    RagChatMessage sendMessage(String conversationId, String content, String userType);

    /**
     * 发送消息并流式返回AI回复
     * <p>处理流程：保存用户消息 → 意图识别 → 根据意图路由到通用聊天或业务回复 → SseEmitter 流式输出</p>
     *
     * @param conversationId 会话ID
     * @param content        用户消息内容
     * @return SseEmitter 流式响应
     */
    SseEmitter sendMessageStream(String conversationId, String content);

    /**
     * 删除会话（逻辑删除）
     *
     * @param conversationId 会话ID
     */
    void deleteConversation(String conversationId);

    /**
     * 更新会话标题
     *
     * @param conversationId 会话ID
     * @param title          新标题
     */
    void updateConversationTitle(String conversationId, String title);
}
