package com.group4.wxai.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable per-user slot draft used while required trip information is being collected.
 */
public record TripPlanDraft(
        String origin,
        String destination,
        String departureTime,
        String duration
) {

    public TripPlanDraft {
        origin = clean(origin);
        destination = clean(destination);
        departureTime = clean(departureTime);
        duration = clean(duration);
    }

    public static TripPlanDraft empty() {
        return new TripPlanDraft("", "", "", "");
    }

    public TripPlanDraft merge(Map<String, String> slots) {
        Map<String, String> safeSlots = slots == null ? Map.of() : slots;
        return new TripPlanDraft(
                prefer(safeSlots.get("origin"), origin),
                prefer(safeSlots.get("destination"), destination),
                prefer(safeSlots.get("departure_time"), departureTime),
                prefer(safeSlots.get("duration"), duration)
        );
    }

    public TripPlanDraft with(String field, String value) {
        return switch (field) {
            case "origin" -> new TripPlanDraft(value, destination, departureTime, duration);
            case "destination" -> new TripPlanDraft(origin, value, departureTime, duration);
            case "departure_time" -> new TripPlanDraft(origin, destination, value, duration);
            case "duration" -> new TripPlanDraft(origin, destination, departureTime, value);
            default -> this;
        };
    }

    public TripPlanRequest toRequest() {
        return new TripPlanRequest(origin, destination, departureTime, duration);
    }

    public Map<String, String> toSlots() {
        Map<String, String> slots = new LinkedHashMap<>();
        slots.put("origin", origin);
        slots.put("destination", destination);
        slots.put("departure_time", departureTime);
        slots.put("duration", duration);
        return slots;
    }

    public String nextMissingField() {
        return toRequest().missingFields().stream().findFirst().orElse("");
    }

    private static String prefer(String candidate, String existing) {
        return candidate == null || candidate.isBlank() ? existing : candidate.trim();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
