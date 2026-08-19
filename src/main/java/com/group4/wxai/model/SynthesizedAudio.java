package com.group4.wxai.model;

import java.util.Locale;

public record SynthesizedAudio(byte[] data, String format) {

    public SynthesizedAudio {
        format = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isEmpty() {
        return data == null || data.length == 0;
    }
}
