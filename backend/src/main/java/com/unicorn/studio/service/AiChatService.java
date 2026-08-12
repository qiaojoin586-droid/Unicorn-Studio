package com.unicorn.studio.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.function.Consumer;

@Service
public class AiChatService {

    private final String aiApiKey;
    private final String aiApiUrl;
    private final String aiModel;
    private final RestTemplate restTemplate;

    public AiChatService(
            @Value("${app.ai.api-key:}") String aiApiKey,
            @Value("${app.ai.api-url:https://api.openai.com/v1/chat/completions}") String aiApiUrl,
            @Value("${app.ai.model:gpt-3.5-turbo}") String aiModel) {
        this.aiApiKey = aiApiKey;
        this.aiApiUrl = aiApiUrl;
        this.aiModel = aiModel;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Stream a response from the AI model.
     * Falls back to a mock response if no API key is configured.
     */
    public void streamResponse(String userMessage, Consumer<String> onChunk) {
        if (aiApiKey == null || aiApiKey.isBlank()) {
            streamMockResponse(userMessage, onChunk);
            return;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(aiApiKey);

            Map<String, Object> requestBody = Map.of(
                    "model", aiModel,
                    "messages", new Object[]{
                            Map.of("role", "system", "content",
                                    "你是独角兽工作室的AI助手，名叫小独角兽。你友好、专业，擅长回答关于工作室、技术学习和编程的问题。请用中文回答。"),
                            Map.of("role", "user", "content", userMessage)
                    },
                    "max_tokens", 1024
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(aiApiUrl, entity, Map.class);

            if (response.getBody() != null) {
                var choices = (java.util.List<Map<String, Object>>) response.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    var message = (Map<String, Object>) choices.get(0).get("message");
                    if (message != null) {
                        String content = (String) message.get("content");
                        if (content != null) {
                            // Split into chunks to simulate streaming
                            int chunkSize = 10;
                            for (int i = 0; i < content.length(); i += chunkSize) {
                                String chunk = content.substring(i, Math.min(i + chunkSize, content.length()));
                                onChunk.accept(chunk);
                                Thread.sleep(50);
                            }
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            onChunk.accept("抱歉，AI 服务暂时不可用。错误: " + e.getMessage());
        }
    }

    /**
     * Mock streaming response for development/testing when no API key is configured.
     */
    private void streamMockResponse(String userMessage, Consumer<String> onChunk) {
        String[] responses = {
                "你好！我是独角兽工作室的 AI 助手。",
                "很高兴为你服务！",
                "关于你的问题「" + userMessage + "」，",
                "我建议你可以参考我们的学习资料页面，",
                "那里有很多优质的教程和手册。",
                "如果有其他问题，随时可以问我！"
        };

        for (String part : responses) {
            onChunk.accept(part);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
