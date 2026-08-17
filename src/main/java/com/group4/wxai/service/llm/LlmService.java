package com.group4.wxai.service.llm;

import com.group4.wxai.model.LlmMessage;

import java.util.List;

public interface LlmService {

    String chatText(String userId, String userMessage, List<LlmMessage> history);

    String chatWithImage(String userId, String prompt, String imageUrl);

    String generateImage(String prompt);

    byte[] textToSpeech(String text);
}
