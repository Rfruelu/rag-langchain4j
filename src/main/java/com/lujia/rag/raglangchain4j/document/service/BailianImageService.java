package com.lujia.rag.raglangchain4j.document.service;

import com.lujia.rag.raglangchain4j.common.config.BailianModelFactory;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Base64;

/**
 * 阿里百炼大模型图片识别服务
 * <p>基于 langchain4j 的 {@link ChatModel}，通过百炼 OpenAI 兼容接口
 * 调用 qwen-vl-max 视觉模型识别图片内容</p>
 *
 * @see <a href="https://help.aliyun.com/zh/model-studio/developer-reference/use-qwen-by-calling-api">百炼 API 文档</a>
 */
@Slf4j
@Service
public class BailianImageService {

    /** 视觉模型名称，默认 qwen-vl-max */
    @Value("${bailian.model:qwen-vl-max}")
    private String model;

    /** 百炼模型工厂（由 common 模块统一提供） */
    private final BailianModelFactory bailianModelFactory;

    /** langchain4j ChatModel 实例（启动时一次性创建） */
    private ChatModel chatModel;

    public BailianImageService(BailianModelFactory bailianModelFactory) {
        this.bailianModelFactory = bailianModelFactory;
    }

    /**
     * 启动时创建 ChatModel 实例，避免懒加载的线程安全问题
     */
    @PostConstruct
    void init() {
        this.chatModel = bailianModelFactory.chatModel(model, Duration.ofSeconds(120));
    }

    /**
     * 获取 ChatModel 实例
     */
    private ChatModel getChatModel() {
        return chatModel;
    }

    /**
     * 识别图片内容并返回描述文本
     * <p>将图片以 base64 编码通过 langchain4j 发送给百炼视觉模型，获取图片内容的文字描述</p>
     *
     * @param imageBytes 图片字节数据
     * @param mimeType   图片 MIME 类型（如 image/png、image/jpeg）
     * @return 图片描述文本
     * @throws RuntimeException 识别失败时抛出
     */
    public String describeImage(byte[] imageBytes, String mimeType) {
        try {
            // 构建 base64 Data URI
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String dataUri = "data:" + mimeType + ";base64," + base64Image;

            // 构建包含图片和文本提示的 UserMessage
            UserMessage userMessage = UserMessage.from(
                    ImageContent.from(dataUri, mimeType),
                    new TextContent("请详细描述这张图片的内容，包括图片中的文字、图表、流程等关键信息。" +
                            "描述将用于替换文档中的图片，使读者无需看图也能理解内容。" +
                            "请直接输出描述，不要添加'这张图片展示了'等前缀。")
            );

            // 调用模型
            log.debug("开始调用百炼视觉模型识别图片, 大小: {} bytes, 类型: {}", imageBytes.length, mimeType);
            ChatResponse chatResponse = getChatModel().chat(userMessage);
            String description = chatResponse.aiMessage().text();
            log.debug("百炼图片识别完成, 描述长度: {}", description.length());

            return description;
        } catch (Exception e) {
            log.error("百炼图片识别异常", e);
            throw new RuntimeException("百炼图片识别失败: " + e.getMessage(), e);
        }
    }
}
