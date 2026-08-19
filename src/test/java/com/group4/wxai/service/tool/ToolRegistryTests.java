package com.group4.wxai.service.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolRegistryTests {

    @Test
    void registersAndExecutesNamedTools() {
        AiTool weather = mock(AiTool.class);
        AiTool placeSearch = mock(AiTool.class);
        when(weather.name()).thenReturn(WeatherTool.NAME);
        when(placeSearch.name()).thenReturn(PlaceSearchTool.NAME);
        when(weather.execute(Map.of("location", "成都"))).thenReturn("成都天气");

        ToolRegistry registry = new ToolRegistry(List.of(weather, placeSearch));

        assertTrue(registry.names().containsAll(List.of(WeatherTool.NAME, PlaceSearchTool.NAME)));
        assertEquals("成都天气", registry.execute(WeatherTool.NAME, Map.of("location", "成都")));
    }

    @Test
    void rejectsDuplicateToolNames() {
        AiTool first = mock(AiTool.class);
        AiTool second = mock(AiTool.class);
        when(first.name()).thenReturn("duplicate");
        when(second.name()).thenReturn("duplicate");

        assertThrows(IllegalStateException.class, () -> new ToolRegistry(List.of(first, second)));
    }
}
