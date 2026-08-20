package com.group4.wxai.service.ilink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.group4.wxai.config.ILinkProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ILinkClientManagerTests {

    @Test
    void protectsLoginOperationsWithConfiguredAdminToken() {
        ILinkProperties properties = new ILinkProperties();
        properties.setAdminToken("correct-token");
        ILinkClientManager manager = new ILinkClientManager(
                properties, new ObjectMapper(), mock(ApplicationEventPublisher.class)
        );
        try {
            assertDoesNotThrow(() -> manager.requireAdminToken("correct-token"));
            assertThrows(SecurityException.class, () -> manager.requireAdminToken("wrong-token"));
        } finally {
            manager.close();
        }
    }
}
