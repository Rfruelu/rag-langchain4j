package com.lujia.rag.raglangchain4j.chat.service;

import com.lujia.rag.raglangchain4j.chat.enums.ChatIntent;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * 用户意图识别 AI 服务
 * <p>基于 langchain4j @AiService 声明式接口，使用轻量模型快速分类用户意图</p>
 * <p>通过 @MemoryId 加载多轮对话上下文，结合历史消息准确识别上下文相关的意图</p>
 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "titleChatModel",
        chatMemoryProvider = "intentChatMemoryProvider")
public interface IntentRecognitionService {

    /**
     * 识别用户消息的业务意图分类
     * <p>框架自动加载该 conversationId 的历史对话作为上下文，
     * 帮助理解“这个多少钱”“怎么办”等依赖上下文的模糊查询</p>
     *
     * @param conversationId 会话ID，用于加载多轮对话上下文
     * @param userMessage    用户消息内容
     * @return 意图分类枚举值
     */
    @SystemMessage("""
            你是一个面料撮合交易平台的用户意图识别助手。该平台提供纺织品面料的在线交易、找布匹配、价格查询等服务。

            请根据用户消息内容，将其分类为以下五种意图之一：
            - PRE_SALES：售前咨询，包括面料咨询、找布需求、面料推荐、样品索取、供应商信息等
            - POST_SALES：售后问题，包括退换货、质量问题、物流查询、投诉建议、订单纠纷等
            - PRICE_INQUIRY：面料价格咨询，包括价格查询、报价请求、费用说明、大货价/剪版布价格等
            - SYSTEM_OPERATION：系统操作咨询，包括平台使用方法、功能操作指引、账号问题、支付流程、登录注册等
            - GENERAL：非业务相关的通用闲聊，与面料交易无关的日常聊天

            分类规则：
            1. 结合对话历史上下文理解用户意图，特别是“这个”“怎么办”“多少钱”等指代性表述
            2. 涉及面料、布料、纺织、印染、供应商、采购商、订单交易等业务话题 → 根据具体场景归入 PRE_SALES / POST_SALES / PRICE_INQUIRY
            3. 涉及平台使用、系统操作、功能说明 → SYSTEM_OPERATION
            4. 问候语如“你好”、“在吗”，或与业务完全无关的话题 → GENERAL
            5. 用户信息输入不完整明确时，需要多轮对话引导用户输入，如“请提供完整的姓名”、“请提供完整的订单号”等

            示例：
            - "有没有32支精梳全棉的卫衣布料推荐？" → PRE_SALES
            - "上次买的面料有色差，怎么处理退货？" → POST_SALES
            - "精棉苏绒大货价多少钱一公斤？" → PRICE_INQUIRY
            - "怎么注册成为供应商？" → SYSTEM_OPERATION
            - "今天天气怎么样？" → GENERAL
            - "你好" → GENERAL
            - "帮我找一下不倒绒的供应商" → PRE_SALES
            - "订单发了三天了怎么还没到？" → POST_SALES
            - "40支密棉奥代尔拉架的剪版布价格是多少？" → PRICE_INQUIRY
            - "怎么修改收货地址？" → SYSTEM_OPERATION

            只输出枚举名称（PRE_SALES / POST_SALES / PRICE_INQUIRY / SYSTEM_OPERATION / GENERAL），不要输出任何其他内容。""")
    @UserMessage("用户消息：{{userMessage}}")
    ChatIntent recognizeIntent(@MemoryId String conversationId, @V("userMessage") String userMessage);
}
