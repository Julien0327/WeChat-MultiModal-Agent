package com.group4.wxai.service.ilink;

import com.github.wechat.ilink.sdk.core.model.ImageItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.group4.wxai.model.ReplyMode;
import com.group4.wxai.service.TextProcessingService;
import com.group4.wxai.service.llm.LlmService;
import com.group4.wxai.service.wx.WxMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ILinkMediaProcessingServiceTests {

    @Mock
    private ILinkClientManager clientManager;
    @Mock
    private LlmService llmService;
    @Mock
    private TextProcessingService textProcessingService;
    @Mock
    private WxMessageService messageService;

    private ILinkMediaProcessingService service;

    @BeforeEach
    void setUp() {
        service = new ILinkMediaProcessingService(
                clientManager, llmService, textProcessingService, messageService
        );
    }

    @Test
    void usesServerTranscriptBeforeDownloadingVoice() throws Exception {
        VoiceItem voice = new VoiceItem();
        voice.setText("现在几点");
        MessageItem item = new MessageItem();
        item.setVoice_item(voice);

        service.processVoiceAsync("user@im.wechat", item);

        verify(textProcessingService).process("user@im.wechat", "现在几点", ReplyMode.TEXT);
        verify(clientManager, never()).downloadMedia(item);
    }

    @Test
    void downloadsImageAndSendsVisionResult() throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        MessageItem item = new MessageItem();
        item.setImage_item(new ImageItem());
        when(clientManager.downloadMedia(item)).thenReturn(png);
        when(llmService.chatWithImageData(
                eq("user@im.wechat"), anyString(), eq(png), eq("image/png")
        )).thenReturn("图里是一只猫");

        service.processImageAsync("user@im.wechat", item);

        verify(messageService).sendTextMessage("user@im.wechat", "图里是一只猫");
    }
}
