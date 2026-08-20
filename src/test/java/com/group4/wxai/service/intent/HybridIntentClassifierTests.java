package com.group4.wxai.service.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.group4.wxai.config.LlmProperties;
import com.group4.wxai.model.IntentResult;
import com.group4.wxai.model.IntentType;
import com.group4.wxai.model.ReplyMode;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HybridIntentClassifierTests {

    @Mock
    private OkHttpClient okHttpClient;

    @Mock
    private Call call;

    private ObjectMapper objectMapper;
    private HybridIntentClassifier classifier;

    @BeforeEach
    void setUp() {
        LlmProperties properties = new LlmProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("https://example.test/api/v1");
        properties.setIntentModel("qwen-flash");
        objectMapper = new ObjectMapper();
        classifier = new HybridIntentClassifier(properties, objectMapper, okHttpClient);
    }

    @Test
    void recognizesHighConfidenceLocalRulesWithoutCallingModel() {
        IntentResult image = classifier.classify("请帮我画一只戴墨镜的猫", List.of());
        IntentResult clear = classifier.classify("清空聊天记录", List.of());
        IntentResult help = classifier.classify("你能做什么", List.of());
        IntentResult voiceHelp = classifier.classify("你会说话吗", List.of());
        IntentResult weather = classifier.classify("成都明天天气怎么样", List.of());
        IntentResult severalDaysWeather = classifier.classify("江油最近几天天气", List.of());
        IntentResult sevenDayWeather = classifier.classify("最近七天江油天气", List.of());
        IntentResult place = classifier.classify("绵阳有哪些景点", List.of());
        IntentResult webSearch = classifier.classify("帮我联网搜索千问最新消息", List.of());
        IntentResult worldClock = classifier.classify("东京现在几点", List.of());
        IntentResult defaultClock = classifier.classify("现在几点", List.of());
        IntentResult weatherWithoutLocation = classifier.classify("今天天气怎么样", List.of());

        assertEquals(IntentType.GENERATE_IMAGE, image.intent());
        assertEquals("一只戴墨镜的猫", image.slot("prompt"));
        assertEquals(IntentType.CLEAR_CONTEXT, clear.intent());
        assertEquals(IntentType.HELP, help.intent());
        assertEquals(IntentType.HELP, voiceHelp.intent());
        assertEquals(IntentType.WEATHER, weather.intent());
        assertEquals("成都", weather.slot("location"));
        assertEquals("TOMORROW", weather.slot("date"));
        assertEquals(IntentType.WEATHER, severalDaysWeather.intent());
        assertEquals("江油", severalDaysWeather.slot("location"));
        assertEquals("NEXT_3_DAYS", severalDaysWeather.slot("date"));
        assertEquals(IntentType.WEATHER, sevenDayWeather.intent());
        assertEquals("江油", sevenDayWeather.slot("location"));
        assertEquals("NEXT_7_DAYS", sevenDayWeather.slot("date"));
        assertEquals(IntentType.PLACE_SEARCH, place.intent());
        assertEquals("绵阳", place.slot("location"));
        assertEquals("景点", place.slot("keyword"));
        assertEquals(IntentType.WEB_SEARCH, webSearch.intent());
        assertEquals("千问最新消息", webSearch.slot("query"));
        assertEquals(IntentType.WORLD_CLOCK, worldClock.intent());
        assertEquals("东京", worldClock.slot("location"));
        assertEquals(IntentType.WORLD_CLOCK, defaultClock.intent());
        assertEquals("北京", defaultClock.slot("location"));
        assertEquals(IntentType.WEATHER, weatherWithoutLocation.intent());
        assertEquals("", weatherWithoutLocation.slot("location"));
        assertEquals("TODAY", weatherWithoutLocation.slot("date"));
        verify(okHttpClient, never()).newCall(any());
    }

    @Test
    void parsesStructuredModelResultForPlaceSearch() throws Exception {
        stubModelResult("""
                {"intent":"PLACE_SEARCH","confidence":0.96,
                 "slots":{"location":"成都","keyword":"火锅店"},"replyMode":"AUTO",
                 "needsClarification":false,"clarificationQuestion":""}
                """);

        IntentResult result = classifier.classify("帮我找一下成都的火锅店", List.of());

        assertEquals(IntentType.PLACE_SEARCH, result.intent());
        assertEquals("成都", result.slot("location"));
        assertEquals("火锅店", result.slot("keyword"));
        assertFalse(result.needsClarification());
    }

    @Test
    void rejectsSentenceFragmentsAsPlaceSearchArguments() throws Exception {
        stubModelResult("""
                {"intent":"PLACE_SEARCH","confidence":1.0,
                 "slots":{"location":"有没","keyword":"近点的啊，我今天晚上还要回来睡觉"},
                 "replyMode":"AUTO","needsClarification":false,"clarificationQuestion":""}
                """);

        IntentResult result = classifier.classify("有没有近点的啊，我今天晚上还要回来睡觉呢", List.of());

        assertEquals(IntentType.CHAT, result.intent());
        assertFalse(result.needsClarification());
    }

    @Test
    void preservesAllTripPlanSchemaFieldsWithoutGuessingMissingValues() throws Exception {
        stubModelResult("""
                {"intent":"TRIP_PLAN","confidence":0.96,
                 "slots":{"origin":"江油","departure_time":"明天","duration":"1天"},
                 "replyMode":"AUTO","needsClarification":true,"clarificationQuestion":"随便猜一个目的地"}
                """);

        IntentResult result = classifier.classify("帮我规划明天从江油出发的一日游", List.of());

        assertEquals(IntentType.TRIP_PLAN, result.intent());
        assertEquals("江油", result.slot("origin"));
        assertEquals("", result.slot("destination"));
        assertEquals("明天", result.slot("departure_time"));
        assertEquals("1天", result.slot("duration"));
        assertEquals("", result.slot("preference"));
        assertFalse(result.needsClarification());
    }

    @Test
    void preservesTripPreferenceSeparatelyFromDestination() throws Exception {
        stubModelResult("""
                {"intent":"TRIP_PLAN","confidence":0.98,
                 "slots":{"origin":"江油","destination":"RECOMMEND","departure_time":"明天",
                 "duration":"7天","preference":"雪山"},
                 "replyMode":"AUTO","needsClarification":false,"clarificationQuestion":""}
                """);

        IntentResult result = classifier.classify(
                "明天从江油出发玩7天，想看雪山，帮我推荐", List.of()
        );

        assertEquals(IntentType.TRIP_PLAN, result.intent());
        assertEquals("RECOMMEND", result.slot("destination"));
        assertEquals("雪山", result.slot("preference"));
    }

    @Test
    void removesWeatherLocationInferredFromHistoryInsteadOfGuessing() throws Exception {
        stubModelResult("""
                {"intent":"WEATHER","confidence":0.97,
                 "slots":{"location":"耀州区","date":"TODAY"},
                 "replyMode":"AUTO","needsClarification":false,"clarificationQuestion":""}
                """);

        IntentResult result = classifier.classify("外面冷不冷", List.of());

        assertEquals(IntentType.WEATHER, result.intent());
        assertEquals("", result.slot("location"));
        assertEquals("TODAY", result.slot("date"));
        assertFalse(result.needsClarification());
    }

    @Test
    void routesDefinitionQuestionsToWebSearchWithoutCallingModel() {
        IntentResult suffixQuestion = classifier.classify("人工智能是什么", List.of());
        IntentResult prefixQuestion = classifier.classify("什么是大语言模型", List.of());

        assertEquals(IntentType.WEB_SEARCH, suffixQuestion.intent());
        assertEquals("人工智能 是什么", suffixQuestion.slot("query"));
        assertEquals(IntentType.WEB_SEARCH, prefixQuestion.intent());
        assertEquals("大语言模型 是什么", prefixQuestion.slot("query"));
        verify(okHttpClient, never()).newCall(any());
    }

    @Test
    void keepsPronounDefinitionQuestionsOutOfBlindSearch() throws Exception {
        stubModelResult("""
                {"intent":"CHAT","confidence":0.95,"slots":{},"replyMode":"AUTO",
                 "needsClarification":false,"clarificationQuestion":""}
                """);

        IntentResult result = classifier.classify("你是什么", List.of());

        assertEquals(IntentType.CHAT, result.intent());
    }

    @Test
    void parsesStructuredModelResultForFuzzyImageRequest() throws Exception {
        stubModelResult("""
                {"intent":"GENERATE_IMAGE","confidence":0.93,
                 "slots":{"prompt":"海边日落"},"needsClarification":false,"clarificationQuestion":""}
                """);

        IntentResult result = classifier.classify("我想要一张海边日落的图", List.of());

        assertEquals(IntentType.GENERATE_IMAGE, result.intent());
        assertEquals("海边日落", result.slot("prompt"));
        assertEquals(0.93, result.confidence(), 0.001);
        assertEquals(ReplyMode.AUTO, result.replyMode());

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient).newCall(requestCaptor.capture());
        Buffer bodyBuffer = new Buffer();
        requestCaptor.getValue().body().writeTo(bodyBuffer);
        JsonNode parameters = objectMapper.readTree(bodyBuffer.readUtf8()).path("parameters");
        assertEquals("json_object", parameters.path("response_format").path("type").asText());
        assertFalse(parameters.has("max_tokens"));
    }

    @Test
    void recognizesExplicitVoiceReplyPreference() throws Exception {
        stubModelResult("""
                {"intent":"CHAT","confidence":0.98,"slots":{},"replyMode":"AUTO",
                 "needsClarification":false,"clarificationQuestion":""}
                """);

        IntentResult result = classifier.classify("请用语音告诉我答案", List.of());

        assertEquals(IntentType.CHAT, result.intent());
        assertEquals(ReplyMode.VOICE, result.replyMode());
    }

    @Test
    void keepsExplicitReplyPreferenceWhenModelResponseCannotBeParsed() throws Exception {
        stubModelResult("not-json");

        IntentResult result = classifier.classify("请用文字回复我", List.of());

        assertEquals(IntentType.CHAT, result.intent());
        assertEquals(ReplyMode.TEXT, result.replyMode());
    }

    @Test
    void asksForClarificationWhenActionConfidenceIsLow() throws Exception {
        stubModelResult("""
                {"intent":"GENERATE_IMAGE","confidence":0.42,
                 "slots":{"prompt":"一张图"},"needsClarification":false,"clarificationQuestion":""}
                """);

        IntentResult result = classifier.classify("帮我弄一个", List.of());

        assertEquals(IntentType.UNKNOWN, result.intent());
        assertTrue(result.needsClarification());
    }

    @Test
    void correctsModelWhenInformationSourceQuestionIsMisclassifiedAsHelp() throws Exception {
        stubModelResult("""
                {"intent":"HELP","confidence":0.9,"slots":{},"replyMode":"AUTO",
                 "needsClarification":false,"clarificationQuestion":""}
                """);

        IntentResult result = classifier.classify("你怎么获取到现在的时间的", List.of());

        assertEquals(IntentType.CHAT, result.intent());
        assertFalse(result.needsClarification());
    }

    @Test
    void fallsBackToChatWhenModelResponseCannotBeParsed() throws Exception {
        stubModelResult("not-json");

        IntentResult result = classifier.classify("画家梵高是谁", List.of());

        assertEquals(IntentType.CHAT, result.intent());
        assertEquals(0.5, result.confidence(), 0.001);
    }

    private void stubModelResult(String modelContent) throws IOException {
        ObjectNode responseJson = objectMapper.createObjectNode();
        ObjectNode message = objectMapper.createObjectNode();
        message.put("content", modelContent);
        ObjectNode choice = objectMapper.createObjectNode();
        choice.set("message", message);
        responseJson.putObject("output").putArray("choices").add(choice);

        Request request = new Request.Builder().url("https://example.test").build();
        Response response = new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(
                        objectMapper.writeValueAsString(responseJson),
                        MediaType.parse("application/json")
                ))
                .build();
        when(okHttpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);
    }
}
