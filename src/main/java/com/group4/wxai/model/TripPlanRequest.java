package com.group4.wxai.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict input contract for the multi-step trip-planning tool.
 */
public record TripPlanRequest(
        String origin,
        String destination,
        String departureTime,
        String duration,
        String preference
) {

    public static final String RECOMMEND_DESTINATION = "RECOMMEND";

    public TripPlanRequest {
        origin = clean(origin);
        destination = clean(destination);
        departureTime = clean(departureTime);
        duration = clean(duration);
        preference = clean(preference);
    }

    public TripPlanRequest(String origin, String destination, String departureTime, String duration) {
        this(origin, destination, departureTime, duration, "");
    }

    public static TripPlanRequest fromSlots(Map<String, String> slots) {
        Map<String, String> safeSlots = slots == null ? Map.of() : slots;
        return new TripPlanRequest(
                safeSlots.get("origin"),
                safeSlots.get("destination"),
                safeSlots.get("departure_time"),
                safeSlots.get("duration"),
                safeSlots.get("preference")
        );
    }

    public Map<String, String> toArguments() {
        Map<String, String> arguments = new LinkedHashMap<>();
        arguments.put("origin", origin);
        arguments.put("destination", destination);
        arguments.put("departure_time", departureTime);
        arguments.put("duration", duration);
        if (!preference.isBlank()) {
            arguments.put("preference", preference);
        }
        return arguments;
    }

    public List<String> missingFields() {
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        if (origin.isBlank()) {
            missing.add("origin");
        }
        if (destination.isBlank()) {
            missing.add("destination");
        }
        if (departureTime.isBlank()) {
            missing.add("departure_time");
        }
        if (duration.isBlank()) {
            missing.add("duration");
        }
        return List.copyOf(missing);
    }

    public boolean isComplete() {
        return missingFields().isEmpty() && !needsDestinationRecommendation();
    }

    public boolean needsDestinationRecommendation() {
        return RECOMMEND_DESTINATION.equalsIgnoreCase(destination);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
