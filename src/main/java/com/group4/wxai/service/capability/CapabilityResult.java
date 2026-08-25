package com.group4.wxai.service.capability;

public record CapabilityResult(boolean ok, String output, String errorMessage) {

    public CapabilityResult {
        output = output == null ? "" : output;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public static CapabilityResult success(String output) {
        return new CapabilityResult(true, output, "");
    }

    public static CapabilityResult failure(String errorMessage) {
        return new CapabilityResult(false, "", errorMessage);
    }
}
