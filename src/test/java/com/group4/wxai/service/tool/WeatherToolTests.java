package com.group4.wxai.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.group4.wxai.service.weather.WeatherService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WeatherToolTests {

    @Test
    void schemaRequiresAnExplicitLocation() throws Exception {
        WeatherTool tool = new WeatherTool(mock(WeatherService.class));
        JsonNode schema = new ObjectMapper().readTree(tool.inputJsonSchema());

        assertEquals(List.of("location"), new ObjectMapper().convertValue(
                schema.path("required"), List.class
        ));
        assertTrue(schema.path("properties").has("location"));
        assertEquals(1, schema.path("properties").path("location").path("minLength").asInt());
        assertFalse(schema.path("additionalProperties").asBoolean(true));
    }
}
