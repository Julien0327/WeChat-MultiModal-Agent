package com.group4.wxai.service;

import com.group4.wxai.model.ReplyMode;
import com.group4.wxai.service.llm.LlmService;
import com.group4.wxai.service.wx.WxMediaService;
import com.group4.wxai.service.wx.WxMessageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceProcessingServiceTests {

    private static final String USER_ID = "voice-user";
    private static final String MEDIA_ID = "media-id";

    @Mock
    private LlmService llmService;

    @Mock
    private WxMediaService wxMediaService;

    @Mock
    private WxMessageService wxMessageService;

    @Mock
    private TextProcessingService textProcessingService;

    @InjectMocks
    private VoiceProcessingService voiceProcessingService;

    @Test
    void routesProgramAsrTranscriptThroughSharedIntentPipelineWithTextDefault() throws Exception {
        byte[] audio = new byte[]{1, 2, 3};
        when(wxMediaService.downloadTemporaryMedia(MEDIA_ID)).thenReturn(audio);
        when(llmService.speechToText(audio, "amr")).thenReturn("生成一张雪山图片");

        voiceProcessingService.processAsync(USER_ID, null, MEDIA_ID, "amr");

        verify(textProcessingService).process(USER_ID, "生成一张雪山图片", ReplyMode.TEXT);
    }

    @Test
    void prefersProgramAsrOverWechatRecognition() throws Exception {
        byte[] audio = new byte[]{4, 5, 6};
        when(wxMediaService.downloadTemporaryMedia(MEDIA_ID)).thenReturn(audio);
        when(llmService.speechToText(audio, "silk")).thenReturn("程序识别结果");

        voiceProcessingService.processAsync(USER_ID, "微信识别结果", MEDIA_ID, "silk");

        verify(textProcessingService).process(USER_ID, "程序识别结果", ReplyMode.TEXT);
        verify(textProcessingService, never()).process(USER_ID, "微信识别结果", ReplyMode.TEXT);
    }

    @Test
    void fallsBackToWechatRecognitionWhenProgramAsrFails() throws Exception {
        when(wxMediaService.downloadTemporaryMedia(MEDIA_ID))
                .thenThrow(new IllegalStateException("download failed"));

        voiceProcessingService.processAsync(USER_ID, "  微信识别结果  ", MEDIA_ID, "amr");

        verify(textProcessingService).process(USER_ID, "微信识别结果", ReplyMode.TEXT);
    }

    @Test
    void sendsTextErrorWhenNoTranscriptIsAvailable() throws Exception {
        when(wxMediaService.downloadTemporaryMedia(MEDIA_ID))
                .thenThrow(new IllegalStateException("download failed"));

        voiceProcessingService.processAsync(USER_ID, " ", MEDIA_ID, "amr");

        verify(textProcessingService, never())
                .process(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
        verify(wxMessageService).sendTextMessage(USER_ID, "抱歉，语音识别或处理失败，请稍后重试。");
    }
}
