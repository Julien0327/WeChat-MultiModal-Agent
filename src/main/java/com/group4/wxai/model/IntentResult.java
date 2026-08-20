package com.group4.wxai.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record IntentResult(
        IntentType intent,
        double confidence,
        Map<String, String> slots,
        ReplyMode replyMode,
        boolean needsClarification,
        String clarificationQuestion
) {

    public IntentResult {
        intent = intent == null ? IntentType.UNKNOWN : intent;
        confidence = Math.max(0, Math.min(1, confidence));
        replyMode = replyMode == null ? ReplyMode.AUTO : replyMode;
        slots = slots == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(slots));
        clarificationQuestion = clarificationQuestion == null ? "" : clarificationQuestion.trim();
    }

    public static IntentResult of(IntentType intent, double confidence) {
        return new IntentResult(intent, confidence, Collections.emptyMap(), ReplyMode.AUTO, false, "");
    }

    public static IntentResult image(double confidence, String prompt) {
        return new IntentResult(
                IntentType.GENERATE_IMAGE,
                confidence,
                Map.of("prompt", prompt == null ? "" : prompt.trim()),
                ReplyMode.AUTO,
                false,
                ""
        );
    }

    public static IntentResult weather(double confidence, String location, String date) {
        Map<String, String> weatherSlots = new LinkedHashMap<>();
        weatherSlots.put("location", location == null ? "" : location.trim());
        weatherSlots.put("date", date == null || date.isBlank() ? "TODAY" : date.trim());
        return new IntentResult(
                IntentType.WEATHER, confidence, weatherSlots, ReplyMode.AUTO, false, ""
        );
    }

    public static IntentResult placeSearch(double confidence, String location, String keyword) {
        Map<String, String> placeSlots = new LinkedHashMap<>();
        placeSlots.put("location", location == null ? "" : location.trim());
        placeSlots.put("keyword", keyword == null ? "" : keyword.trim());
        return new IntentResult(
                IntentType.PLACE_SEARCH, confidence, placeSlots, ReplyMode.AUTO, false, ""
        );
    }

    public static IntentResult webSearch(double confidence, String query) {
        return new IntentResult(
                IntentType.WEB_SEARCH,
                confidence,
                Map.of("query", query == null ? "" : query.trim()),
                ReplyMode.AUTO,
                false,
                ""
        );
    }

    public static IntentResult worldClock(double confidence, String location) {
        return new IntentResult(
                IntentType.WORLD_CLOCK,
                confidence,
                Map.of("location", location == null ? "" : location.trim()),
                ReplyMode.AUTO,
                false,
                ""
        );
    }

    public static IntentResult tripPlan(double confidence, String origin, String destination,
                                        String departureTime, String duration) {
        return tripPlan(confidence, origin, destination, departureTime, duration, "");
    }

    public static IntentResult tripPlan(double confidence, String origin, String destination,
                                        String departureTime, String duration, String preference) {
        TripPlanRequest request = new TripPlanRequest(
                origin, destination, departureTime, duration, preference
        );
        Map<String, String> slots = new LinkedHashMap<>(request.toArguments());
        slots.putIfAbsent("preference", request.preference());
        return new IntentResult(
                IntentType.TRIP_PLAN,
                confidence,
                slots,
                ReplyMode.AUTO,
                false,
                ""
        );
    }

    public static IntentResult unknown(String question) {
        return new IntentResult(IntentType.UNKNOWN, 0, Collections.emptyMap(), ReplyMode.AUTO, true, question);
    }

    public String slot(String name) {
        return slots.getOrDefault(name, "");
    }
}
