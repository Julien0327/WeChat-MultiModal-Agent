package com.group4.wxai.service.skill;

public record SkillResult(boolean ok, String output, String errorMessage) {

    public SkillResult {
        output = output == null ? "" : output;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public static SkillResult success(String output) {
        return new SkillResult(true, output, "");
    }

    public static SkillResult failure(String errorMessage) {
        return new SkillResult(false, "", errorMessage);
    }
}
