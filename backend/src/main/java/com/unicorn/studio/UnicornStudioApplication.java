package com.unicorn.studio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用入口。
 * <p>
 * <b>关于 Spring AI 自动配置的排除（重要，勿删）</b>：
 * 本项目通过 {@code spring-ai-starter-model-openai} 引入 Spring AI，但 API Key / 地址 / 模型
 * 都存在数据库（{@code site_settings} 的 {@code ai} 组）并由后台随时修改，
 * 因此 {@code ChatModel} 是运行时按配置<b>手动构建</b>的（见 {@code AIWorker.ChatClientFactory}），
 * 不使用 starter 的自动装配。
 * <p>
 * 若不排除，starter 会尝试自动创建全部 6 类模型 Bean（chat/embedding/image/audio-speech/
 * audio-transcription/moderation），其中 {@code OpenAiAudioSpeechModel} 会在启动时强制校验
 * {@code spring.ai.openai.api-key} —— 而该 key 我们故意不放在配置文件里（禁止明文/入库配置双份），
 * 结果就是<b>应用启动直接失败</b>。
 * <p>
 * 排除后：Spring AI 的底层依赖（OpenAiApi / OpenAiChatModel / ToolCallback 等）仍然可用，
 * 仅“不由 Boot 自动创建 Bean”，功能不受影响。
 */
@SpringBootApplication(exclude = {
        org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration.class,
})
public class UnicornStudioApplication {

    public static void main(String[] args) {
        SpringApplication.run(UnicornStudioApplication.class, args);
    }
}
