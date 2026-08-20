package com.group4.wxai.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class JokeTool implements AiTool {

    public static final String NAME = "joke";
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    @Value("${team-tools.joke.api-url:}")
    private String apiUrl;

    @Value("${team-tools.joke.api-key:}")
    private String apiKey;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "从笑话数据接口随机获取一条笑话，不由模型现场编造。";
    }

    @Override
    public String execute(Map<String, String> arguments) {
        if (apiUrl == null || apiUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("笑话工具尚未配置，请填写 JOKE_API_URL 和 JOKE_API_KEY。");
        }
        HttpUrl base = HttpUrl.parse(apiUrl);
        if (base == null) {
            throw new IllegalStateException("笑话 API 地址格式不正确，请检查 JOKE_API_URL。");
        }
        HttpUrl url = base.newBuilder().addQueryParameter("key", apiKey).build();
        try (Response response = okHttpClient.newCall(new Request.Builder().url(url).get().build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("笑话 API 请求失败，HTTP " + response.code() + "。");
            }
            JsonNode root = objectMapper.readTree(body);
            JsonNode result = root.path("result");
            JsonNode items = result.isArray() ? result : result.path("data");
            if (!items.isArray() || items.isEmpty()) {
                throw new IllegalStateException(root.path("reason").asText("笑话 API 没有返回内容。"));
            }
            JsonNode first = items.get(0);
            String content = first.path("content").asText(first.path("text").asText()).trim();
            if (content.isEmpty()) {
                throw new IllegalStateException("笑话 API 返回内容为空。");
            }
            return "😂 " + content;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("笑话 API 暂时不可用。", e);
        }
    }
}
