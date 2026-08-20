package com.group4.wxai.service.tool;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldClockToolTests {

    private final WorldClockTool tool = new WorldClockTool(
            Clock.fixed(Instant.parse("2026-08-20T08:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void reportsCityTimeAndDifferenceFromBeijingWithDst() {
        String tokyo = tool.execute(Map.of("location", "东京"));
        String london = tool.execute(Map.of("location", "伦敦"));

        assertTrue(tokyo.contains("2026年8月20日 星期四 17:00:00"));
        assertTrue(tokyo.contains("UTC+09:00"));
        assertTrue(tokyo.contains("比北京时间快1小时"));
        assertTrue(london.contains("2026年8月20日 星期四 09:00:00"));
        assertTrue(london.contains("Europe/London（UTC+01:00）"));
        assertTrue(london.contains("比北京时间慢7小时"));
    }

    @Test
    void supportsExplicitUtcOffset() {
        String result = tool.execute(Map.of("location", "UTC+5:30"));

        assertTrue(result.contains("13:30:00"));
        assertTrue(result.contains("UTC+05:30"));
        assertTrue(result.contains("比北京时间慢2小时30分钟"));
    }

    @Test
    void defaultsToBeijingWhenLocationIsMissing() {
        String result = tool.execute(Map.of());

        assertTrue(result.contains("🕒 北京时间"));
        assertTrue(result.contains("2026年8月20日 星期四 16:00:00"));
        assertTrue(result.contains("Asia/Shanghai（UTC+08:00）"));
        assertTrue(result.contains("与北京时间相同"));
    }

    @Test
    void asksForClearerLocationWhenTimezoneCannotBeResolved() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(Map.of("location", "某个神秘地方"))
        );

        assertTrue(error.getMessage().contains("无法确定"));
    }
}
