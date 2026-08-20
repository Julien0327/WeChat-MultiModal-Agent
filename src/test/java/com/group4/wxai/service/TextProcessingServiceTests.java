package com.group4.wxai.service;

import com.group4.wxai.model.ChatContext;
import com.group4.wxai.model.IntentResult;
import com.group4.wxai.model.IntentType;
import com.group4.wxai.model.LlmMessage;
import com.group4.wxai.model.ReplyMode;
import com.group4.wxai.model.SynthesizedAudio;
import com.group4.wxai.service.intent.IntentClassifier;
import com.group4.wxai.service.llm.LlmService;
import com.group4.wxai.service.tool.PlaceSearchTool;
import com.group4.wxai.service.tool.ToolRegistry;
import com.group4.wxai.service.tool.WeatherTool;
import com.group4.wxai.service.tool.WebSearchTool;
import com.group4.wxai.service.tool.WorldClockTool;
import com.group4.wxai.service.wx.WxMessageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TextProcessingServiceTests {

    private static final String USER_ID = "text-user";

    @Mock
    private IntentClassifier intentClassifier;

    @Mock
    private LlmService llmService;

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private TripPlanningService tripPlanningService;

    @Mock
    private WxMessageService wxMessageService;

    @InjectMocks
    private TextProcessingService textProcessingService;

    @BeforeEach
    @AfterEach
    void clearContext() {
        ChatContext.clear(USER_ID);
    }

    @Test
    void routesImageIntentWithExtractedPrompt() {
        when(intentClassifier.classify(eq("我想要一张海边日落的图"), anyList()))
                .thenReturn(IntentResult.image(0.95, "海边日落"));
        when(llmService.generateImage("海边日落")).thenReturn("https://example.test/image.png");

        textProcessingService.processAsync(USER_ID, "我想要一张海边日落的图");

        verify(llmService).generateImage("海边日落");
        verify(wxMessageService).sendImageMessage(USER_ID, "https://example.test/image.png");
        assertEquals(2, ChatContext.getHistory(USER_ID).size());
    }

    @Test
    void routesWeatherIntentToRealtimeWeatherService() {
        IntentResult weatherIntent = IntentResult.weather(0.99, "成都", "TOMORROW");
        when(intentClassifier.classify(eq("成都明天天气怎么样"), anyList())).thenReturn(weatherIntent);
        when(toolRegistry.execute(WeatherTool.NAME, Map.of("location", "成都", "date", "TOMORROW")))
                .thenReturn("成都明天：多云，20～28℃");

        textProcessingService.processAsync(USER_ID, "成都明天天气怎么样");

        verify(toolRegistry).execute(WeatherTool.NAME, Map.of("location", "成都", "date", "TOMORROW"));
        verify(wxMessageService).sendTextMessage(USER_ID, "成都明天：多云，20～28℃");
        verify(llmService, never()).chatText(eq(USER_ID), eq("成都明天天气怎么样"), anyList());
        assertEquals(2, ChatContext.getHistory(USER_ID).size());
    }

    @Test
    void inheritsLastLocationForDateOnlyWeatherFollowUp() {
        ChatContext.rememberLocation(USER_ID, "江油");
        when(intentClassifier.classify(eq("那最近七天呢"), anyList()))
                .thenReturn(IntentResult.of(IntentType.CHAT, 0.9));
        when(toolRegistry.execute(WeatherTool.NAME, Map.of(
                "location", "江油", "date", "NEXT_7_DAYS"
        ))).thenReturn("江油未来七天天气");

        textProcessingService.processAsync(USER_ID, "那最近七天呢");

        verify(toolRegistry).execute(WeatherTool.NAME, Map.of(
                "location", "江油", "date", "NEXT_7_DAYS"
        ));
        verify(intentClassifier).classify(eq("那最近七天呢"), anyList());
        verify(wxMessageService).sendTextMessage(USER_ID, "江油未来七天天气");
    }

    @Test
    void asksForWeatherLocationWithoutCallingTheTool() {
        IntentResult missingLocation = IntentResult.weather(0.99, "", "TODAY");
        when(intentClassifier.classify(eq("今天天气怎么样"), anyList())).thenReturn(missingLocation);

        textProcessingService.processAsync(USER_ID, "今天天气怎么样");

        verify(toolRegistry, never()).execute(eq(WeatherTool.NAME), org.mockito.ArgumentMatchers.anyMap());
        verify(wxMessageService).sendTextMessage(
                USER_ID, "你要查哪个城市或地区的天气？请告诉我具体地点。"
        );
        assertEquals("TODAY", ChatContext.getPendingWeatherDate(USER_ID).orElseThrow());
        assertEquals(2, ChatContext.getHistory(USER_ID).size());
    }

    @Test
    void fillsPendingWeatherLocationFromTheNextMessageAndKeepsRequestedDate() {
        ChatContext.rememberPendingWeather(USER_ID, "TOMORROW");
        when(intentClassifier.classify(eq("江油"), anyList()))
                .thenReturn(IntentResult.of(IntentType.CHAT, 0.9));
        when(toolRegistry.execute(WeatherTool.NAME, Map.of(
                "location", "江油", "date", "TOMORROW"
        ))).thenReturn("江油明天天气");

        textProcessingService.processAsync(USER_ID, "江油");

        verify(toolRegistry).execute(WeatherTool.NAME, Map.of(
                "location", "江油", "date", "TOMORROW"
        ));
        verify(wxMessageService).sendTextMessage(USER_ID, "江油明天天气");
        assertTrue(ChatContext.getPendingWeatherDate(USER_ID).isEmpty());
    }

    @Test
    void letsTripPlanningConsumeDepartureDateBeforeWeatherFollowUp() {
        ChatContext.rememberLocation(USER_ID, "耀州区");
        IntentResult chat = IntentResult.of(IntentType.CHAT, 0.9);
        when(intentClassifier.classify(eq("明天"), anyList())).thenReturn(chat);
        when(tripPlanningService.shouldHandlePendingAnswer(USER_ID, "明天", chat)).thenReturn(true);
        when(tripPlanningService.handleTurn(USER_ID, "明天", chat))
                .thenReturn(new TripPlanningService.TripPlanTurn("这次准备玩多久？", false));

        textProcessingService.processAsync(USER_ID, "明天");

        verify(tripPlanningService).handleTurn(USER_ID, "明天", chat);
        verify(toolRegistry, never()).execute(eq(WeatherTool.NAME), org.mockito.ArgumentMatchers.anyMap());
        verify(wxMessageService).sendTextMessage(USER_ID, "这次准备玩多久？");
    }

    @Test
    void routesPlaceSearchIntentToRegisteredTool() {
        IntentResult placeIntent = IntentResult.placeSearch(0.98, "绵阳", "景点");
        when(intentClassifier.classify(eq("绵阳有哪些景点"), anyList())).thenReturn(placeIntent);
        when(toolRegistry.execute(PlaceSearchTool.NAME, Map.of("location", "绵阳", "keyword", "景点")))
                .thenReturn("📍 绵阳 · 景点\n1. 越王楼");

        textProcessingService.processAsync(USER_ID, "绵阳有哪些景点");

        verify(toolRegistry).execute(
                PlaceSearchTool.NAME, Map.of("location", "绵阳", "keyword", "景点")
        );
        verify(wxMessageService).sendTextMessage(USER_ID, "📍 绵阳 · 景点\n1. 越王楼");
        verify(llmService, never()).chatText(eq(USER_ID), eq("绵阳有哪些景点"), anyList());
        assertEquals(2, ChatContext.getHistory(USER_ID).size());
    }

    @Test
    void routesWebSearchIntentToRegisteredTool() {
        IntentResult searchIntent = IntentResult.webSearch(0.99, "千问最新消息");
        when(intentClassifier.classify(eq("联网搜索千问最新消息"), anyList())).thenReturn(searchIntent);
        when(toolRegistry.execute(WebSearchTool.NAME, Map.of("query", "千问最新消息")))
                .thenReturn("🔎 联网搜索：千问最新消息\n来源：官方消息");

        textProcessingService.processAsync(USER_ID, "联网搜索千问最新消息");

        verify(toolRegistry).execute(WebSearchTool.NAME, Map.of("query", "千问最新消息"));
        verify(wxMessageService).sendTextMessage(
                USER_ID, "🔎 联网搜索：千问最新消息\n来源：官方消息"
        );
    }

    @Test
    void routesWorldClockIntentToRegisteredTool() {
        IntentResult clockIntent = IntentResult.worldClock(1, "伦敦");
        when(intentClassifier.classify(eq("伦敦现在几点"), anyList())).thenReturn(clockIntent);
        when(toolRegistry.execute(WorldClockTool.NAME, Map.of("location", "伦敦")))
                .thenReturn("🕒 伦敦时间\n当地时间：2026年8月20日 09:00:00");

        textProcessingService.processAsync(USER_ID, "伦敦现在几点");

        verify(toolRegistry).execute(WorldClockTool.NAME, Map.of("location", "伦敦"));
        verify(wxMessageService).sendTextMessage(
                USER_ID, "🕒 伦敦时间\n当地时间：2026年8月20日 09:00:00"
        );
    }

    @Test
    void routesTripPlanningThroughTheGuardedConversationService() {
        IntentResult tripIntent = IntentResult.tripPlan(0.99, "江油", "", "明天", "1天");
        when(intentClassifier.classify(eq("帮我规划明天从江油出发的一日游"), anyList()))
                .thenReturn(tripIntent);
        when(tripPlanningService.handleTurn(USER_ID, "帮我规划明天从江油出发的一日游", tripIntent))
                .thenReturn(new TripPlanningService.TripPlanTurn(
                        "你想去哪里？请给我目的城市或地区；如果还没想好，可以直接说“帮我推荐”。", false
                ));

        textProcessingService.processAsync(USER_ID, "帮我规划明天从江油出发的一日游");

        verify(tripPlanningService).handleTurn(
                USER_ID, "帮我规划明天从江油出发的一日游", tripIntent
        );
        verify(toolRegistry, never()).execute(eq("trip_plan"), org.mockito.ArgumentMatchers.anyMap());
        assertEquals(2, ChatContext.getHistory(USER_ID).size());
    }

    @Test
    void clearsConversationHistoryForClearIntent() {
        ChatContext.addUserMessage(USER_ID, "旧消息");
        when(intentClassifier.classify(eq("清空聊天记录"), anyList()))
                .thenReturn(IntentResult.of(IntentType.CLEAR_CONTEXT, 1));

        textProcessingService.processAsync(USER_ID, "清空聊天记录");

        assertTrue(ChatContext.getHistory(USER_ID).isEmpty());
        verify(wxMessageService).sendTextMessage(USER_ID, "已清除本次对话记录，我们可以重新开始。");
    }

    @Test
    void sendsClarificationWithoutExecutingAnAction() {
        IntentResult ambiguous = new IntentResult(
                IntentType.UNKNOWN, 0.3, Map.of(), ReplyMode.AUTO,
                true, "你希望我生成图片还是普通聊天？"
        );
        when(intentClassifier.classify(eq("帮我弄一个"), anyList())).thenReturn(ambiguous);

        textProcessingService.processAsync(USER_ID, "帮我弄一个");

        verify(wxMessageService).sendTextMessage(USER_ID, "你希望我生成图片还是普通聊天？");
        verify(llmService, never()).generateImage(org.mockito.ArgumentMatchers.anyString());
        verify(llmService, never()).chatText(eq(USER_ID), eq("帮我弄一个"), anyList());
    }

    @Test
    void usesVoiceReplyWhenTextUserExplicitlyRequestsIt() {
        IntentResult voiceChat = new IntentResult(
                IntentType.CHAT, 0.98, Map.of(), ReplyMode.VOICE, false, ""
        );
        SynthesizedAudio audio = new SynthesizedAudio(new byte[]{1, 2}, "mp3");
        when(intentClassifier.classify(eq("请用语音回答"), anyList())).thenReturn(voiceChat);
        when(llmService.chatText(eq(USER_ID), eq("请用语音回答"), anyList())).thenReturn("语音答案");
        when(llmService.textToSpeech("语音答案")).thenReturn(audio);
        when(wxMessageService.sendVoiceMessage(USER_ID, audio)).thenReturn(true);
        when(wxMessageService.sendMp3DownloadLink(USER_ID, audio)).thenReturn(true);

        textProcessingService.processAsync(USER_ID, "请用语音回答");

        verify(wxMessageService).sendVoiceMessage(USER_ID, audio);
        verify(wxMessageService).sendMp3DownloadLink(USER_ID, audio);
        verify(wxMessageService, never()).sendTextMessage(USER_ID, "语音答案");
    }

    @Test
    void fallsBackToTextWhenVoiceReplyCannotBeSent() {
        IntentResult voiceChat = new IntentResult(
                IntentType.CHAT, 0.98, Map.of(), ReplyMode.VOICE, false, ""
        );
        SynthesizedAudio audio = new SynthesizedAudio(new byte[]{3, 4}, "mp3");
        when(intentClassifier.classify(eq("语音回复我"), anyList())).thenReturn(voiceChat);
        when(llmService.chatText(eq(USER_ID), eq("语音回复我"), anyList())).thenReturn("备用文字答案");
        when(llmService.textToSpeech("备用文字答案")).thenReturn(audio);
        when(wxMessageService.sendVoiceMessage(USER_ID, audio)).thenReturn(false);
        when(wxMessageService.sendMp3DownloadLink(USER_ID, audio)).thenReturn(false);

        textProcessingService.processAsync(USER_ID, "语音回复我");

        verify(wxMessageService).sendTextMessage(USER_ID, "备用文字答案");
    }

    @Test
    void keepsMp3ReplyWhenWechatVoiceBubbleCannotBeSent() {
        IntentResult voiceChat = new IntentResult(
                IntentType.CHAT, 0.98, Map.of(), ReplyMode.VOICE, false, ""
        );
        SynthesizedAudio audio = new SynthesizedAudio(new byte[]{5, 6}, "mp3");
        when(intentClassifier.classify(eq("发语音给我"), anyList())).thenReturn(voiceChat);
        when(llmService.chatText(eq(USER_ID), eq("发语音给我"), anyList())).thenReturn("MP3答案");
        when(llmService.textToSpeech("MP3答案")).thenReturn(audio);
        when(wxMessageService.sendVoiceMessage(USER_ID, audio)).thenReturn(false);
        when(wxMessageService.sendMp3DownloadLink(USER_ID, audio)).thenReturn(true);

        textProcessingService.processAsync(USER_ID, "发语音给我");

        verify(wxMessageService).sendMp3DownloadLink(USER_ID, audio);
        verify(wxMessageService, never()).sendTextMessage(USER_ID, "MP3答案");
    }

    @Test
    void explicitTextPreferenceOverridesVoiceChannelDefault() {
        IntentResult textChat = new IntentResult(
                IntentType.CHAT, 0.98, Map.of(), ReplyMode.TEXT, false, ""
        );
        when(intentClassifier.classify(eq("请用文字回答"), anyList())).thenReturn(textChat);
        when(llmService.chatText(eq(USER_ID), eq("请用文字回答"), anyList())).thenReturn("文字答案");

        textProcessingService.process(USER_ID, "请用文字回答", ReplyMode.VOICE);

        verify(wxMessageService).sendTextMessage(USER_ID, "文字答案");
        verify(llmService, never()).textToSpeech(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void routesOrdinaryMessageToChatAndStoresOneConversationTurn() {
        when(intentClassifier.classify(eq("你好"), anyList()))
                .thenReturn(IntentResult.of(IntentType.CHAT, 0.98));
        when(llmService.chatText(eq(USER_ID), eq("你好"), anyList())).thenReturn("你好呀");

        textProcessingService.processAsync(USER_ID, "你好");

        ArgumentCaptor<List<LlmMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService).chatText(eq(USER_ID), eq("你好"), historyCaptor.capture());
        assertTrue(historyCaptor.getValue().isEmpty());
        assertEquals(2, ChatContext.getHistory(USER_ID).size());
        verify(wxMessageService).sendTextMessage(USER_ID, "你好呀");
    }
}
