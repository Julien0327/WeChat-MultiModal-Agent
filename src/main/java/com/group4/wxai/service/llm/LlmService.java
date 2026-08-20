package com.group4.wxai.service.llm;

import com.group4.wxai.model.LlmMessage;
import com.group4.wxai.model.SynthesizedAudio;

import java.util.List;

public interface LlmService {

    String chatText(String userId, String userMessage, List<LlmMessage> history);

    String chatWithImage(String userId, String prompt, String imageUrl);

    String chatWithImageData(String userId, String prompt, byte[] imageData, String mimeType);

    String generateImage(String prompt);

    String speechToText(byte[] audioData, String audioFormat);

    SynthesizedAudio textToSpeech(String text);
}
