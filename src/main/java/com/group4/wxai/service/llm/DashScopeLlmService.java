package com.group4.wxai.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.group4.wxai.config.LlmProperties;
import com.group4.wxai.model.LlmMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashScopeLlmService implements LlmService {

    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private static final String SYSTEM_PROMPT =
            "你是一个智能客服助手，请友好、简洁地回答用户的问题。";

    @Override
    public String chatText(String userId, String userMessage, List<LlmMessage> history) {
        log.info("LLM文本对话: user={}, message={}", userId, userMessage);

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", llmProperties.getTextModel());
            requestBody.put("input", buildInputMessages(history, userMessage));
            requestBody.put("parameters", buildParameters());

            JsonNode response = postJson(llmProperties.getBaseUrl() + "/services/aigc/text-generation/generation",
                    requestBody);
            String reply = response.path("output").path("text").asText();
            log.info("LLM回复: user={}, reply={}", userId, reply);
            return reply;
        } catch (Exception e) {
            log.error("LLM文本对话失败: user={}", userId, e);
            return "抱歉，AI服务暂时不可用，请稍后重试。";
        }
    }

    @Override
    public String chatWithImage(String userId, String prompt, String imageUrl) {
        log.info("LLM图片理解: user={}, imageUrl={}", userId, imageUrl);

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", llmProperties.getVisionModel());

            ObjectNode input = objectMapper.createObjectNode();
            ArrayNode messages = objectMapper.createArrayNode();

            ObjectNode userMsg = objectMapper.createObjectNode();
            userMsg.put("role", "user");
            ArrayNode content = objectMapper.createArrayNode();

            ObjectNode imageContent = objectMapper.createObjectNode();
            imageContent.put("image", imageUrl);
            content.add(imageContent);

            ObjectNode textContent = objectMapper.createObjectNode();
            textContent.put("text", prompt);
            content.add(textContent);

            userMsg.set("content", content);
            messages.add(userMsg);
            input.set("messages", messages);
            requestBody.set("input", input);

            JsonNode response = postJson(llmProperties.getBaseUrl() + "/services/aigc/multimodal-generation/generation",
                    requestBody);
            String reply = response.path("output").path("choices").path(0).path("message").path("content")
                    .path(0).path("text").asText();
            if (reply.isEmpty()) {
                reply = response.path("output").path("text").asText();
            }
            log.info("LLM图片理解回复: user={}, reply={}", userId, reply);
            return reply;
        } catch (Exception e) {
            log.error("LLM图片理解失败: user={}", userId, e);
            return "抱歉，图片分析服务暂时不可用，请稍后重试。";
        }
    }

    @Override
    public String generateImage(String prompt) {
        log.info("LLM图片生成: prompt={}", prompt);

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", llmProperties.getImageModel());
            ObjectNode input = objectMapper.createObjectNode();
            input.put("prompt", prompt);
            requestBody.set("input", input);

            JsonNode response = postJson(llmProperties.getBaseUrl() + "/services/aigc/text2image/image-synthesis",
                    requestBody);

            String imageUrl = response.path("output").path("url").asText();
            if (imageUrl.isEmpty()) {
                imageUrl = response.path("output").path("results").path(0).path("url").asText();
            }
            log.info("LLM图片生成成功: url={}", imageUrl);
            return imageUrl;
        } catch (Exception e) {
            log.error("LLM图片生成失败", e);
            return null;
        }
    }

    @Override
    public byte[] textToSpeech(String text) {
        log.info("LLM语音合成: text={}", text);

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", llmProperties.getTtsModel());
            ObjectNode input = objectMapper.createObjectNode();
            input.put("text", text);
            requestBody.set("input", input);

            JsonNode response = postJson(llmProperties.getBaseUrl() + "/services/audio/tts/generation",
                    requestBody);

            String audioUrl = response.path("output").path("audio").path("url").asText();
            if (audioUrl.isEmpty()) {
                log.warn("语音合成未返回音频URL");
                return null;
            }

            return downloadFile(audioUrl);
        } catch (Exception e) {
            log.error("LLM语音合成失败", e);
            return null;
        }
    }

    private ObjectNode buildInputMessages(List<LlmMessage> history, String currentMessage) {
        ObjectNode input = objectMapper.createObjectNode();
        ArrayNode messages = objectMapper.createArrayNode();

        ObjectNode systemMsg = objectMapper.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", SYSTEM_PROMPT);
        messages.add(systemMsg);

        for (LlmMessage msg : history) {
            ObjectNode historyMsg = objectMapper.createObjectNode();
            historyMsg.put("role", msg.getRole());
            historyMsg.put("content", msg.getContent());
            messages.add(historyMsg);
        }

        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", currentMessage);
        messages.add(userMsg);

        input.set("messages", messages);
        return input;
    }

    private ObjectNode buildParameters() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("result_format", "message");
        params.put("temperature", 0.8);
        params.put("max_tokens", 2048);
        return params;
    }

    private JsonNode postJson(String url, ObjectNode body) throws IOException {
        String json = objectMapper.writeValueAsString(body);
        log.debug("POST {} body: {}", url, json);

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + llmProperties.getApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            log.debug("API响应: status={}, body={}", response.code(), responseBody);
            if (!response.isSuccessful()) {
                throw new RuntimeException("API调用失败: " + response.code() + ", body: " + responseBody);
            }
            return objectMapper.readTree(responseBody);
        }
    }

    private byte[] downloadFile(String url) throws IOException {
        Request request = new Request.Builder().url(url).build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new RuntimeException("下载文件失败: " + response.code());
            }
            return response.body().bytes();
        }
    }
}
