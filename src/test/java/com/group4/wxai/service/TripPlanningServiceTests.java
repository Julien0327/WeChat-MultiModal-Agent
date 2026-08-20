package com.group4.wxai.service;

import com.group4.wxai.model.IntentResult;
import com.group4.wxai.model.IntentType;
import com.group4.wxai.model.TripPlanDraft;
import com.group4.wxai.service.llm.LlmService;
import com.group4.wxai.service.tool.PlaceSearchTool;
import com.group4.wxai.service.tool.ToolRegistry;
import com.group4.wxai.service.tool.TripPlanningTool;
import com.group4.wxai.service.tool.WebSearchTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripPlanningServiceTests {

    private static final String USER_ID = "trip-user";

    @Mock
    private PlaceSearchTool placeSearchTool;
    @Mock
    private WebSearchTool webSearchTool;
    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private LlmService llmService;

    private TripPlanningService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(
                Instant.parse("2026-08-20T08:00:00Z"), ZoneId.of("Asia/Shanghai")
        );
        service = new TripPlanningService(
                placeSearchTool, webSearchTool, toolRegistry, llmService, fixedClock
        );
    }

    @Test
    void asksForMissingFieldsAndDoesNotCallTools() {
        IntentResult partial = IntentResult.tripPlan(0.99, "", "", "明天", "2天");

        TripPlanningService.TripPlanTurn turn = service.handleTurn(USER_ID, "帮我规划一次出行", partial);

        assertEquals("你从哪里出发？请给我具体的城市或地区。", turn.reply());
        TripPlanDraft draft = service.pendingDraft(USER_ID).orElseThrow();
        assertEquals("2026-08-21", draft.departureTime());
        verifyNoInteractions(placeSearchTool, webSearchTool, toolRegistry, llmService);
    }

    @Test
    void doesNotConsumeAnUnrelatedToolIntentWhileWaitingForAnAnswer() {
        service.handleTurn(USER_ID, "帮我规划", IntentResult.tripPlan(0.99, "", "成都", "明天", "1天"));
        IntentResult weather = IntentResult.weather(1, "北京", "TODAY");

        assertFalse(service.shouldHandlePendingAnswer(USER_ID, "北京今天天气怎么样", weather));
        assertFalse(service.shouldHandlePendingAnswer(
                USER_ID, "我现在要聊天，你别乱调用工具了", IntentResult.of(IntentType.CHAT, 0.98)
        ));
    }

    @Test
    void acceptsAValidDepartureDateEvenIfClassifierMistakesItForWeather() {
        service.handleTurn(USER_ID, "帮我规划", IntentResult.tripPlan(
                0.99, "江油", "RECOMMEND", "", "1天"
        ));
        IntentResult mistakenWeather = IntentResult.weather(0.99, "", "TOMORROW");

        assertTrue(service.shouldHandlePendingAnswer(USER_ID, "明天", mistakenWeather));
    }

    @Test
    void extractsTonightAndOneNightFromTheSameFollowUp() {
        service.handleTurn(USER_ID, "我今天晚上想出去玩，可以去哪里", IntentResult.tripPlan(
                0.99, "江油", "RECOMMEND", "", ""
        ));
        when(placeSearchTool.execute(Map.of("location", "江油", "keyword", "旅游景点")))
                .thenReturn("高德候选地点");
        when(webSearchTool.execute(Map.of(
                "query", "从江油出发 2026-08-20 晚上 一晚上 周边游目的地推荐 最新攻略"
        ))).thenReturn("联网候选地点");
        when(llmService.chatText(eq("trip-destination-recommendation"), anyString(), eq(List.of())))
                .thenReturn("今晚可以考虑这些地方，你选哪一个？");
        IntentResult mistakenWeather = IntentResult.weather(0.99, "", "TODAY");

        assertTrue(service.shouldHandlePendingAnswer(
                USER_ID, "就今天晚上出发，玩一晚上", mistakenWeather
        ));
        TripPlanningService.TripPlanTurn turn = service.handleTurn(
                USER_ID, "就今天晚上出发，玩一晚上", mistakenWeather
        );

        assertEquals("今晚可以考虑这些地方，你选哪一个？", turn.reply());
        TripPlanDraft draft = service.pendingDraft(USER_ID).orElseThrow();
        assertEquals("2026-08-20 晚上", draft.departureTime());
        assertEquals("一晚上", draft.duration());
        assertEquals("", draft.destination());
        verify(toolRegistry, never()).execute(eq(TripPlanningTool.NAME), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void extractsOriginImmediateDepartureAndDurationFromOneCompoundReply() {
        service.handleTurn(USER_ID, "我想出去玩，可以去哪里", IntentResult.tripPlan(
                0.99, "", "RECOMMEND", "", ""
        ));
        when(placeSearchTool.execute(Map.of("location", "江油太平镇", "keyword", "旅游景点")))
                .thenReturn("江油本地候选");
        when(webSearchTool.execute(Map.of(
                "query", "从江油太平镇出发 2026-08-20 16:00 一晚上 周边游目的地推荐 最新攻略"
        ))).thenReturn("联网候选");
        when(llmService.chatText(eq("trip-destination-recommendation"), anyString(), eq(List.of())))
                .thenReturn("给你几个近处候选，你选哪个？");

        IntentResult modelGuess = IntentResult.tripPlan(
                0.99, "江油太平镇", "我在江油啊", "", ""
        );
        TripPlanningService.TripPlanTurn turn = service.handleTurn(
                USER_ID, "我现在在江油太平镇，就现在出发，玩一晚上", modelGuess
        );

        assertEquals("给你几个近处候选，你选哪个？", turn.reply());
        TripPlanDraft draft = service.pendingDraft(USER_ID).orElseThrow();
        assertEquals("江油太平镇", draft.origin());
        assertEquals("2026-08-20 16:00", draft.departureTime());
        assertEquals("一晚上", draft.duration());
        assertEquals("", draft.destination());
    }

    @Test
    void ignoresModelFilledDestinationWhenUserIsOnlyCorrectingOrigin() {
        service.handleTurn(USER_ID, "帮我规划", IntentResult.tripPlan(
                0.99, "江油太平镇", "", "明天", "一晚上"
        ));

        TripPlanningService.TripPlanTurn turn = service.handleTurn(
                USER_ID,
                "我在江油啊",
                IntentResult.tripPlan(0.99, "江油", "我在江油啊", "明天", "一晚上")
        );

        assertEquals("你想去哪里？请给我目的城市或地区；如果还没想好，可以直接说“帮我推荐”。", turn.reply());
        TripPlanDraft draft = service.pendingDraft(USER_ID).orElseThrow();
        assertEquals("江油", draft.origin());
        assertEquals("", draft.destination());
        verify(toolRegistry, never()).execute(eq(TripPlanningTool.NAME), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void keepsNearbyReturnTonightPreferenceInsidePendingTripFlow() {
        when(placeSearchTool.execute(Map.of("location", "江油", "keyword", "旅游景点")))
                .thenReturn("江油本地候选");
        when(webSearchTool.execute(anyMap())).thenReturn("联网候选");
        when(llmService.chatText(eq("trip-destination-recommendation"), anyString(), eq(List.of())))
                .thenReturn("候选列表");
        service.handleTurn(USER_ID, "帮我推荐", IntentResult.tripPlan(
                0.99, "江油", "RECOMMEND", "今天晚上", "一晚上"
        ));
        String refinement = "有没有近点的啊，我今天晚上还要回来睡觉呢";
        IntentResult badPlaceIntent = IntentResult.placeSearch(1, "有没", "近点的啊");

        assertTrue(service.shouldHandlePendingAnswer(USER_ID, refinement, badPlaceIntent));
        TripPlanningService.TripPlanTurn turn = service.handleTurn(USER_ID, refinement, badPlaceIntent);

        assertEquals("候选列表", turn.reply());
        assertEquals("江油", service.pendingDraft(USER_ID).orElseThrow().origin());
        verify(toolRegistry, never()).execute(eq(TripPlanningTool.NAME), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void completesThePendingFieldBeforeExecutingTheRegisteredPlanner() {
        service.handleTurn(USER_ID, "帮我规划", IntentResult.tripPlan(
                0.99, "江油", "", "明天", "1天"
        ));
        IntentResult chat = IntentResult.of(IntentType.CHAT, 0.9);
        when(toolRegistry.execute(TripPlanningTool.NAME, Map.of(
                "origin", "江油",
                "destination", "成都",
                "departure_time", "2026-08-21",
                "duration", "1天"
        ))).thenReturn("成都一日游计划");

        assertTrue(service.shouldHandlePendingAnswer(USER_ID, "成都", chat));
        TripPlanningService.TripPlanTurn turn = service.handleTurn(USER_ID, "成都", chat);

        assertEquals("成都一日游计划", turn.reply());
        assertTrue(turn.completed());
        assertTrue(service.pendingDraft(USER_ID).isEmpty());
    }

    @Test
    void recommendsDestinationsWithAmapAndWebButWaitsForUserChoice() {
        when(placeSearchTool.execute(Map.of("location", "江油", "keyword", "旅游景点")))
                .thenReturn("高德候选地点");
        when(webSearchTool.execute(Map.of(
                "query", "从江油出发 2026-08-21 1天 周边游目的地推荐 最新攻略"
        ))).thenReturn("联网候选地点");
        when(llmService.chatText(eq("trip-destination-recommendation"), anyString(), eq(List.of())))
                .thenReturn("候选有成都、绵阳。你选哪一个？");

        TripPlanningService.TripPlanTurn turn = service.handleTurn(USER_ID, "帮我推荐目的地", IntentResult.tripPlan(
                0.99, "江油", "RECOMMEND", "明天", "1天"
        ));

        assertEquals("候选有成都、绵阳。你选哪一个？", turn.reply());
        assertFalse(turn.completed());
        assertEquals("", service.pendingDraft(USER_ID).orElseThrow().destination());
        verify(toolRegistry, never()).execute(eq(TripPlanningTool.NAME), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void cancellationClearsDraftWithoutCallingAnything() {
        service.handleTurn(USER_ID, "帮我规划", IntentResult.tripPlan(0.99, "", "成都", "明天", "1天"));

        TripPlanningService.TripPlanTurn turn = service.handleTurn(
                USER_ID, "算了", IntentResult.of(IntentType.CHAT, 0.9)
        );

        assertEquals("好，已经取消这次出行规划。", turn.reply());
        assertTrue(service.pendingDraft(USER_ID).isEmpty());
        verifyNoInteractions(placeSearchTool, webSearchTool, toolRegistry, llmService);
    }
}
