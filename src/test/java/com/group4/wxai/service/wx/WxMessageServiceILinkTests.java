package com.group4.wxai.service.wx;

import com.group4.wxai.service.audio.AudioDownloadService;
import com.group4.wxai.service.ilink.ILinkClientManager;
import me.chanjar.weixin.mp.api.WxMpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WxMessageServiceILinkTests {

    @Mock
    private WxMpService wxMpService;
    @Mock
    private WxMediaService wxMediaService;
    @Mock
    private AudioDownloadService audioDownloadService;
    @Mock
    private ILinkClientManager clientManager;

    private WxMessageService service;

    @BeforeEach
    void setUp() {
        service = new WxMessageService(
                wxMpService, wxMediaService, audioDownloadService, clientManager
        );
    }

    @Test
    void sendsILinkUsersThroughSdkInsteadOfOfficialAccountApi() throws Exception {
        when(clientManager.isILinkUser("user@im.wechat")).thenReturn(true);

        service.sendTextMessage("user@im.wechat", "你好");

        verify(clientManager).sendText("user@im.wechat", "你好");
        verify(wxMpService, never()).getKefuService();
    }
}
