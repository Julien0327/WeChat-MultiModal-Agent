package com.group4.wxai.controller;

import com.group4.wxai.service.ilink.ILinkClientManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ILinkAdminControllerTests {

    @Test
    void rendersLoginContentAsPngQrCode() throws Exception {
        ILinkClientManager manager = mock(ILinkClientManager.class);
        when(manager.startLogin(false)).thenReturn("https://weixin.qq.com/x/test-login");
        ILinkAdminController controller = new ILinkAdminController(manager);

        byte[] png = controller.loginQrImage("admin-token", false);

        verify(manager).requireAdminToken("admin-token");
        verify(manager).startLogin(false);
        assertTrue(png.length > 100);
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 'P', png[1]);
        assertEquals((byte) 'N', png[2]);
        assertEquals((byte) 'G', png[3]);
    }
}
