package com.group4.wxai.service.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.group4.wxai.config.WeatherProperties;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaceSearchToolTests {

    @Mock
    private OkHttpClient okHttpClient;

    @Mock
    private Call call;

    private PlaceSearchTool tool;

    @BeforeEach
    void setUp() {
        WeatherProperties properties = new WeatherProperties();
        properties.setApiKey("test-amap-key");
        properties.setBaseUrl("https://restapi.amap.test/v3");
        tool = new PlaceSearchTool(okHttpClient, new ObjectMapper(), properties);
    }

    @Test
    void searchesWithinCityAndFormatsTopPlaces() throws Exception {
        when(okHttpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(jsonResponse("""
                {"status":"1","info":"OK","pois":[
                  {"name":"越王楼","cityname":"绵阳市","adname":"涪城区",
                   "address":"剑南路东段","type":"风景名胜;风景名胜相关;旅游景点"},
                  {"name":"科技馆","cityname":"绵阳市","adname":"游仙区",
                   "address":[],"type":"科教文化服务;科技馆"}
                ]}
                """));

        String result = tool.execute(Map.of("location", "绵阳", "keyword", "景点"));

        assertTrue(result.contains("📍 绵阳 · 景点"));
        assertTrue(result.contains("1. 越王楼"));
        assertTrue(result.contains("绵阳市涪城区剑南路东段"));
        assertTrue(result.contains("2. 科技馆"));
        assertTrue(result.contains("数据来源：高德地图"));

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient).newCall(requestCaptor.capture());
        Request request = requestCaptor.getValue();
        assertEquals("绵阳", request.url().queryParameter("city"));
        assertEquals("景点", request.url().queryParameter("keywords"));
        assertEquals("true", request.url().queryParameter("citylimit"));
        assertEquals("5", request.url().queryParameter("offset"));
    }

    @Test
    void returnsClearMessageWhenNoPlaceMatches() throws Exception {
        when(okHttpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(jsonResponse("{" +
                "\"status\":\"1\",\"info\":\"OK\",\"pois\":[]}"));

        String result = tool.execute(Map.of("location", "江油", "keyword", "不存在地点"));

        assertTrue(result.contains("没有找到"));
        assertTrue(result.contains("江油"));
    }

    private Response jsonResponse(String json) throws IOException {
        Request request = new Request.Builder().url("https://example.test").build();
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(json, MediaType.parse("application/json")))
                .build();
    }
}
