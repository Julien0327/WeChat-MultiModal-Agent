package com.group4.wxai.service.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.group4.wxai.config.WebSearchProperties;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSearchToolTests {

    @Test
    void searchesAndFormatsAnswerWithSources() throws Exception {
        OkHttpClient client = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        WebSearchProperties properties = new WebSearchProperties();
        properties.setApiKey("tvly-test-key");
        properties.setBaseUrl("https://api.tavily.test");
        WebSearchTool tool = new WebSearchTool(client, new ObjectMapper(), properties);

        when(client.newCall(any())).thenReturn(call);
        Request placeholder = new Request.Builder().url("https://api.tavily.test/search").build();
        when(call.execute()).thenReturn(new Response.Builder()
                .request(placeholder)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create("""
                        {"answer":"这是最新信息。","results":[
                          {"title":"官方消息","url":"https://example.com/news"},
                          {"title":"恶意链接","url":"javascript:alert(1)"}
                        ]}
                        """, MediaType.parse("application/json")))
                .build());

        String result = tool.execute(Map.of("query", "千问最新消息"));

        assertTrue(result.contains("这是最新信息"));
        assertTrue(result.contains("官方消息"));
        assertTrue(result.contains("https://example.com/news"));
        assertTrue(!result.contains("javascript:"));

        org.mockito.ArgumentCaptor<Request> captor = org.mockito.ArgumentCaptor.forClass(Request.class);
        verify(client).newCall(captor.capture());
        assertEquals("Bearer tvly-test-key", captor.getValue().header("Authorization"));
        Buffer buffer = new Buffer();
        captor.getValue().body().writeTo(buffer);
        assertTrue(buffer.readUtf8().contains("千问最新消息"));
    }

    @Test
    void rejectsSearchWhenApiKeyIsMissing() {
        WebSearchTool tool = new WebSearchTool(
                mock(OkHttpClient.class), new ObjectMapper(), new WebSearchProperties()
        );

        assertThrows(IllegalStateException.class,
                () -> tool.execute(Map.of("query", "测试")));
    }
}
