package com.group4.wxai.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.group4.wxai.service.llm.LlmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripPlanningToolTests {

    @Mock
    private WeatherTool weatherTool;
    @Mock
    private PlaceSearchTool placeSearchTool;
    @Mock
    private WebSearchTool webSearchTool;
    @Mock
    private LlmService llmService;

    private TripPlanningTool tool;

    @BeforeEach
    void setUp() {
        tool = new TripPlanningTool(weatherTool, placeSearchTool, webSearchTool, llmService);
    }

    @Test
    void schemaRequiresEveryUserConfirmedField() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(tool.inputJsonSchema());

        assertEquals("object", schema.path("type").asText());
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        assertEquals(List.of("origin", "destination", "departure_time", "duration"),
                new ObjectMapper().convertValue(schema.path("required"), List.class));
        assertTrue(schema.path("properties").has("origin"));
        assertTrue(schema.path("properties").has("departure_time"));
        assertTrue(schema.path("properties").has("duration"));
    }

    @Test
    void refusesIncompleteInputBeforeCallingAnyTool() {
        Map<String, String> incomplete = Map.of(
                "origin", "江油",
                "destination", "成都",
                "departure_time", "2026-08-22"
        );

        assertThrows(IllegalArgumentException.class, () -> tool.execute(incomplete));

        verifyNoInteractions(weatherTool, placeSearchTool, webSearchTool, llmService);
    }

    @Test
    void executesWeatherThenAmapThenWebSearchAndFinallyComposesPlan() {
        Map<String, String> arguments = Map.of(
                "origin", "江油",
                "destination", "成都",
                "departure_time", "2026-08-22 08:00",
                "duration", "1天"
        );
        when(weatherTool.execute(Map.of("location", "成都", "date", "2026-08-22")))
                .thenReturn("成都天气资料");
        when(placeSearchTool.execute(Map.of("location", "成都", "keyword", "旅游景点")))
                .thenReturn("成都高德地点资料");
        when(webSearchTool.execute(Map.of(
                "query", "成都 2026-08-22 08:00 1天 旅行攻略 营业时间 预约 最新"
        ))).thenReturn("成都联网资料");
        when(llmService.chatText(eq("trip-plan-tool"), anyString(), eq(List.of())))
                .thenReturn("可执行的一日游行程");

        String result = tool.execute(arguments);

        assertEquals("可执行的一日游行程", result);
        InOrder order = inOrder(weatherTool, placeSearchTool, webSearchTool, llmService);
        order.verify(weatherTool).execute(Map.of("location", "成都", "date", "2026-08-22"));
        order.verify(placeSearchTool).execute(Map.of("location", "成都", "keyword", "旅游景点"));
        order.verify(webSearchTool).execute(Map.of(
                "query", "成都 2026-08-22 08:00 1天 旅行攻略 营业时间 预约 最新"
        ));
        order.verify(llmService).chatText(eq("trip-plan-tool"), anyString(), eq(List.of()));
    }
}
