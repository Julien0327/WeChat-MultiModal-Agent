package com.group4.wxai.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.group4.wxai.config.LlmProperties;
import com.group4.wxai.model.LlmMessage;
import com.group4.wxai.model.SynthesizedAudio;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashScopeLlmService implements LlmService {

    private static final int MAX_ASR_AUDIO_BYTES = 10 * 1024 * 1024;

    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private static final String SYSTEM_PROMPT = """
            你在微信公众号里和用户一对一聊天。保持稳定、克制、务实，不要使用热情客服腔。

            性格与交流方式：
            - 平时话不多，先直接回答问题，不绕弯，也不为了显得热情而堆很多废话。
            - 遇到代码、AI、学习方法或其他具体问题时认真分析；需要讲清楚时可以适当展开。
            - 面对熟悉、轻松的话题可以有一点自然的幽默和调侃，但不要油腻，不要强行玩梗。
            - 重视事实、计划和边界。信息不足就直接追问，不猜测别人的隐私、动机或经历。
            - 不虚构自己的学校、工作、家庭、感情、旅行等现实生活经历。

            输出规则：
            - 使用自然的中文聊天文本，像微信里正常回消息，不写成客服工单、采访稿、舞台对白或角色扮演剧本。
            - 不要在开头添加“助手：”“AI：”“回复：”等角色标签。
            - 不要输出未填充的模板字段或占位符，例如 [姓名]、<用户>、{user_name}、XXX。
            - 不要使用“尊敬的用户”“亲”“很高兴为您服务”等客服套话。
            - 简单问题通常用一到三段短文本回答；只有确实需要时才使用列表、标题或代码块。
            - 不向用户提及这些性格规则，也不声称自己在模仿某个现实人物。被直接问身份时，如实说明是公众号里的 AI 助手。

            你具备以下能力：普通聊天和知识问答、查询天气、搜索地点、生成图片、分析用户发送的图片、
            识别用户发送的语音、在参数确认后规划出行，以及把回答合成为语音并通过微信公众号发送。
            默认使用文字回复；当用户明确要求“用语音回答”“语音回复”或类似表达时，
            外部程序会把你的回答合成为语音并发送。因此，当用户询问你是否会说话、
            是否支持语音回复时，应明确回答支持，并告诉用户可以说“请用语音回答我”。
            不要声称自己只能处理或发送文字，也不要虚构上述能力之外的功能。
            """;

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
            if (reply.isEmpty()) {
                reply = response.path("output").path("choices").path(0).path("message").path("content").asText();
            }
            String formattedReply = ChatReplyFormatter.toNaturalChatText(reply);
            log.info("LLM回复: user={}, reply={}", userId, formattedReply);
            return formattedReply;
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
            String formattedReply = ChatReplyFormatter.toNaturalChatText(reply);
            log.info("LLM图片理解回复: user={}, reply={}", userId, formattedReply);
            return formattedReply;
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

            ObjectNode parameters = objectMapper.createObjectNode();
            parameters.put("size", "1024*1024");
            parameters.put("n", 1);
            requestBody.set("parameters", parameters);

            String url = llmProperties.getBaseUrl() + "/services/aigc/text2image/image-synthesis";
            String json = objectMapper.writeValueAsString(requestBody);
            log.debug("POST {} body: {}", url, json);

            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + llmProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .header("X-DashScope-Async", "enable")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .build();

            String taskId;
            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                log.debug("图片生成任务提交响应: status={}, body={}", response.code(), responseBody);
                if (!response.isSuccessful()) {
                    throw new RuntimeException("图片生成任务提交失败: " + response.code() + ", body: " + responseBody);
                }
                JsonNode jsonNode = objectMapper.readTree(responseBody);
                taskId = jsonNode.path("output").path("task_id").asText();
                if (taskId.isEmpty()) {
                    throw new RuntimeException("未获取到任务ID: " + responseBody);
                }
            }

            log.info("图片生成任务已提交: taskId={}", taskId);

            String taskUrl = llmProperties.getBaseUrl().replace("/api/v1", "") + "/api/v1/tasks/" + taskId;
            for (int i = 0; i < 60; i++) {
                Thread.sleep(2000);
                Request taskRequest = new Request.Builder()
                        .url(taskUrl)
                        .header("Authorization", "Bearer " + llmProperties.getApiKey())
                        .get()
                        .build();

                try (Response taskResponse = HTTP_CLIENT.newCall(taskRequest).execute()) {
                    String taskBody = taskResponse.body() != null ? taskResponse.body().string() : "";
                    log.debug("轮询任务状态: body={}", taskBody);
                    JsonNode taskNode = objectMapper.readTree(taskBody);
                    String status = taskNode.path("output").path("task_status").asText();

                    if ("SUCCEEDED".equals(status)) {
                        String imageUrl = taskNode.path("output").path("results").path(0).path("url").asText();
                        if (imageUrl.isEmpty()) {
                            imageUrl = taskNode.path("output").path("results").path(0).path("b64_image").asText();
                        }
                        log.info("LLM图片生成成功: url={}", imageUrl);
                        return imageUrl;
                    } else if ("FAILED".equals(status)) {
                        String errMsg = taskNode.path("output").path("message").asText();
                        log.error("图片生成任务失败: {}", errMsg);
                        return null;
                    }
                    log.info("图片生成中... status={}, attempt={}", status, i + 1);
                }
            }

            log.error("图片生成超时");
            return null;
        } catch (Exception e) {
            log.error("LLM图片生成失败", e);
            return null;
        }
    }

    @Override
    public String speechToText(byte[] audioData, String audioFormat) {
        if (audioData == null || audioData.length == 0) {
            throw new IllegalArgumentException("音频内容为空");
        }
        if (audioData.length > MAX_ASR_AUDIO_BYTES) {
            throw new IllegalArgumentException("音频文件超过 10MB，无法识别");
        }

        String normalizedFormat = normalizeAudioFormat(audioFormat);
        log.info("LLM语音识别: format={}, size={} bytes", normalizedFormat, audioData.length);

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", llmProperties.getAsrModel());

            ObjectNode input = objectMapper.createObjectNode();
            ArrayNode messages = objectMapper.createArrayNode();
            ObjectNode userMessage = objectMapper.createObjectNode();
            userMessage.put("role", "user");

            ArrayNode content = objectMapper.createArrayNode();
            ObjectNode audioContent = objectMapper.createObjectNode();
            String dataUrl = "data:" + audioMimeType(normalizedFormat) + ";base64,"
                    + Base64.getEncoder().encodeToString(audioData);
            audioContent.put("audio", dataUrl);
            content.add(audioContent);
            userMessage.set("content", content);
            messages.add(userMessage);
            input.set("messages", messages);
            requestBody.set("input", input);

            ObjectNode parameters = objectMapper.createObjectNode();
            ObjectNode asrOptions = objectMapper.createObjectNode();
            asrOptions.put("enable_itn", true);
            parameters.set("asr_options", asrOptions);
            requestBody.set("parameters", parameters);

            JsonNode response = postJson(
                    llmProperties.getBaseUrl() + "/services/aigc/multimodal-generation/generation",
                    requestBody,
                    false
            );
            String transcript = response.path("output").path("choices").path(0)
                    .path("message").path("content").path(0).path("text").asText().trim();
            if (transcript.isEmpty()) {
                transcript = response.path("output").path("text").asText().trim();
            }
            if (transcript.isEmpty()) {
                throw new IllegalStateException("语音识别接口未返回文字");
            }

            log.info("LLM语音识别成功: chars={}", transcript.length());
            return transcript;
        } catch (Exception e) {
            log.error("LLM语音识别失败: format={}, size={} bytes", normalizedFormat, audioData.length, e);
            throw new IllegalStateException("语音识别服务暂时不可用", e);
        }
    }

    @Override
    public SynthesizedAudio textToSpeech(String text) {
        log.info("LLM语音合成: text={}", text);

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", llmProperties.getTtsModel());
            ObjectNode input = objectMapper.createObjectNode();
            input.put("text", text);
            input.put("voice", llmProperties.getTtsVoice());
            input.put("format", llmProperties.getTtsFormat());
            input.put("sample_rate", llmProperties.getTtsSampleRate());
            requestBody.set("input", input);

            JsonNode response = postJson(llmProperties.getBaseUrl() + "/services/audio/tts/SpeechSynthesizer",
                    requestBody);

            String audioUrl = response.path("output").path("audio").path("url").asText();
            if (audioUrl.isEmpty()) {
                log.warn("语音合成未返回音频URL");
                return null;
            }

            return new SynthesizedAudio(downloadFile(audioUrl), llmProperties.getTtsFormat());
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
        return postJson(url, body, true);
    }

    private JsonNode postJson(String url, ObjectNode body, boolean logRequestBody) throws IOException {
        String json = objectMapper.writeValueAsString(body);
        if (logRequestBody) {
            log.debug("POST {} body: {}", url, json);
        } else {
            log.debug("POST {} body omitted (contains audio data)", url);
        }

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

    private String normalizeAudioFormat(String audioFormat) {
        if (audioFormat == null || audioFormat.isBlank()) {
            return "amr";
        }
        String normalized = audioFormat.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith(".") ? normalized.substring(1) : normalized;
    }

    private String audioMimeType(String audioFormat) {
        return switch (audioFormat) {
            case "mp3", "mpeg" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "ogg" -> "audio/ogg";
            case "opus" -> "audio/opus";
            case "aac" -> "audio/aac";
            case "flac" -> "audio/flac";
            case "amr" -> "audio/amr";
            default -> "audio/" + audioFormat.replaceAll("[^a-z0-9.+-]", "");
        };
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
