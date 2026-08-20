package com.group4.wxai.service.intent;

import com.group4.wxai.model.IntentResult;
import com.group4.wxai.model.LlmMessage;

import java.util.List;

public interface IntentClassifier {

    IntentResult classify(String userMessage, List<LlmMessage> history);
}
