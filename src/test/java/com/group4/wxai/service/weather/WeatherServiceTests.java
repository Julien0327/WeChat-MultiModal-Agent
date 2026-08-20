package com.group4.wxai.service.weather;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeatherServiceTests {

    @Mock
    private OkHttpClient okHttpClient;

    @Mock
    private Call geocodingCall;

    @Mock
    private Call forecastCall;

    @Mock
    private Call liveCall;

    private WeatherService weatherService;

    @BeforeEach
    void setUp() {
        weatherService = new WeatherService(okHttpClient, new ObjectMapper(), new WeatherProperties());
    }

    @Test
    void resolvesLocationAndFormatsCurrentWeather() throws Exception {
        when(okHttpClient.newCall(any())).thenReturn(geocodingCall, forecastCall);
        when(geocodingCall.execute()).thenReturn(jsonResponse("""
                {"results":[{"name":"北京","latitude":39.9042,"longitude":116.4074,
                "admin1":"北京市","country":"中国","population":21540000}]}
                """));
        when(forecastCall.execute()).thenReturn(jsonResponse("""
                {
                  "current":{"time":"2026-08-18T17:00","temperature_2m":29.4,
                    "relative_humidity_2m":62,"apparent_temperature":31.1,
                    "precipitation":0.0,"weather_code":2,"wind_speed_10m":8.6},
                  "daily":{"time":["2026-08-18"],"weather_code":[2],
                    "temperature_2m_max":[31.2],"temperature_2m_min":[23.8],
                    "precipitation_probability_max":[20],
                    "sunrise":["2026-08-18T05:25"],"sunset":["2026-08-18T19:05"]}
                }
                """));

        String result = weatherService.getWeather("北京市", "TODAY");

        assertTrue(result.contains("📍 北京 · 北京市 · 中国"));
        assertTrue(result.contains("现在：多云，29.4℃"));
        assertTrue(result.contains("今天（8月18日"));
        assertTrue(result.contains("23.8～31.2℃"));
        assertTrue(result.contains("最高降水概率 20%"));

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient, org.mockito.Mockito.times(2)).newCall(requestCaptor.capture());
        List<Request> requests = requestCaptor.getAllValues();
        assertEquals("北京市", requests.get(0).url().queryParameter("name"));
        assertEquals("39.9042", requests.get(1).url().queryParameter("latitude"));
        assertTrue(requests.get(1).url().queryParameter("current").contains("temperature_2m"));
    }

    @Test
    void reportsUnknownLocationClearly() throws Exception {
        when(okHttpClient.newCall(any())).thenReturn(geocodingCall);
        when(geocodingCall.execute()).thenReturn(jsonResponse("{\"results\":[]}"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> weatherService.getWeather("不存在城", "TODAY")
        );

        assertTrue(error.getMessage().contains("没有找到"));
    }

    @Test
    void usesAmapWhenWebServiceKeyIsConfigured() throws Exception {
        WeatherProperties properties = new WeatherProperties();
        properties.setApiKey("test-amap-key");
        properties.setBaseUrl("https://restapi.amap.test/v3");
        weatherService = new WeatherService(okHttpClient, new ObjectMapper(), properties);

        when(okHttpClient.newCall(any())).thenReturn(geocodingCall, liveCall, forecastCall);
        when(geocodingCall.execute()).thenReturn(jsonResponse("""
                {"status":"1","info":"OK","geocodes":[{"province":"四川省",
                "city":"成都市","district":"武侯区","adcode":"510107"}]}
                """));
        when(liveCall.execute()).thenReturn(jsonResponse("""
                {"status":"1","info":"OK","lives":[{"weather":"多云","temperature":"29",
                "winddirection":"南","windpower":"3","humidity":"61",
                "reporttime":"2026-08-18 17:00:00"}]}
                """));
        when(forecastCall.execute()).thenReturn(jsonResponse("""
                {"status":"1","info":"OK","forecasts":[{"reporttime":"2026-08-18 17:00:00",
                "casts":[{"date":"2026-08-18","dayweather":"多云","nightweather":"阵雨",
                "daytemp":"31","nighttemp":"23","daywind":"南","daypower":"3"}]}]}
                """));

        String result = weatherService.getWeather("成都市武侯区", "TODAY");

        assertTrue(result.contains("📍 武侯区 · 四川省 · 中国"));
        assertTrue(result.contains("现在：多云，29℃"));
        assertTrue(result.contains("多云转阵雨，23～31℃"));

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient, org.mockito.Mockito.times(3)).newCall(requestCaptor.capture());
        List<Request> requests = requestCaptor.getAllValues();
        assertEquals("成都市武侯区", requests.get(0).url().queryParameter("address"));
        assertEquals("510107", requests.get(1).url().queryParameter("city"));
        assertEquals("base", requests.get(1).url().queryParameter("extensions"));
        assertEquals("all", requests.get(2).url().queryParameter("extensions"));
    }

    @Test
    void usesAmapCoordinatesForSevenDayForecast() throws Exception {
        WeatherProperties properties = new WeatherProperties();
        properties.setApiKey("test-amap-key");
        properties.setBaseUrl("https://restapi.amap.test/v3");
        weatherService = new WeatherService(okHttpClient, new ObjectMapper(), properties);

        when(okHttpClient.newCall(any())).thenReturn(geocodingCall, forecastCall);
        when(geocodingCall.execute()).thenReturn(jsonResponse("""
                {"status":"1","info":"OK","geocodes":[{"province":"四川省",
                "city":"绵阳市","district":"江油市","adcode":"510781",
                "location":"104.745,31.778"}]}
                """));
        when(forecastCall.execute()).thenReturn(jsonResponse("""
                {
                  "current":{"time":"2026-08-20T12:00"},
                  "daily":{
                    "time":["2026-08-20","2026-08-21","2026-08-22","2026-08-23","2026-08-24","2026-08-25","2026-08-26"],
                    "weather_code":[1,2,3,61,80,2,1],
                    "temperature_2m_max":[31,30,29,27,28,30,32],
                    "temperature_2m_min":[23,22,22,21,21,22,23],
                    "precipitation_probability_max":[10,20,20,70,60,20,10],
                    "sunrise":["06:00","06:01","06:01","06:02","06:03","06:03","06:04"],
                    "sunset":["19:40","19:39","19:38","19:37","19:36","19:35","19:34"]
                  }
                }
                """));

        String result = weatherService.getWeather("江油", "NEXT_7_DAYS");

        assertTrue(result.contains("📍 江油市 · 四川省 · 中国"));
        assertTrue(result.contains("今天（8月20日"));
        assertTrue(result.contains("8月26日"));

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient, org.mockito.Mockito.times(2)).newCall(requestCaptor.capture());
        assertEquals("restapi.amap.test", requestCaptor.getAllValues().get(0).url().host());
        assertEquals("江油", requestCaptor.getAllValues().get(0).url().queryParameter("address"));
        assertEquals("api.open-meteo.com", requestCaptor.getAllValues().get(1).url().host());
        assertEquals("31.778", requestCaptor.getAllValues().get(1).url().queryParameter("latitude"));
        assertEquals("104.745", requestCaptor.getAllValues().get(1).url().queryParameter("longitude"));
        assertEquals("7", requestCaptor.getAllValues().get(1).url().queryParameter("forecast_days"));
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
