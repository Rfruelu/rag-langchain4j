package com.lujia.rag.raglangchain4j.chat.controller;

import com.lujia.rag.raglangchain4j.chat.entity.RagChatConversation;
import com.lujia.rag.raglangchain4j.chat.entity.RagChatMessage;
import com.lujia.rag.raglangchain4j.chat.service.RagChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI对话接口
 * <p>提供会话管理、消息收发等 RESTful API</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
public class RagChatController {

    @Autowired
    private RagChatService chatService;

    /**
     * 创建新会话
     *
     * @param params 包含 userId（可选）和 title（可选）
     * @return 创建的会话信息
     */
    @PostMapping("/conversations")
    public Map<String, Object> createConversation(@RequestBody(required = false) Map<String, String> params) {
        String userId = params != null ? params.get("userId") : null;
        String title = params != null ? params.get("title") : null;

        RagChatConversation conversation = chatService.createConversation(userId, title);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", conversation);
        return result;
    }

    /**
     * 查询用户的会话列表
     *
     * @param userId 用户ID（可选，默认 default）
     * @return 会话列表
     */
    @GetMapping("/conversations")
    public Map<String, Object> listConversations(
            @RequestParam(value = "userId", required = false) String userId) {

        List<RagChatConversation> conversations = chatService.listConversations(userId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", conversations);
        return result;
    }

    /**
     * 获取指定会话的消息列表
     *
     * @param conversationId 会话ID
     * @return 消息列表
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public Map<String, Object> listMessages(@PathVariable String conversationId) {
        List<RagChatMessage> messages = chatService.listMessages(conversationId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", messages);
        return result;
    }

    /**
     * 发送消息并流式返回AI回复（SSE）
     * <p>返回 text/event-stream，前端通过 ReadableStream 逐 token 接收</p>
     *
     * @param conversationId 会话ID
     * @param params         包含 content（用户消息内容）
     * @return SseEmitter 流式响应
     */
    @PostMapping(value = "/conversations/{conversationId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(
            @PathVariable String conversationId,
            @RequestBody Map<String, String> params) {

        String content = params.get("content");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }

        return chatService.sendMessageStream(conversationId, content);
    }

    /**
     * 删除会话
     *
     * @param conversationId 会话ID
     * @return 操作结果
     */
    @DeleteMapping("/conversations/{conversationId}")
    public Map<String, Object> deleteConversation(@PathVariable String conversationId) {
        chatService.deleteConversation(conversationId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }

}
