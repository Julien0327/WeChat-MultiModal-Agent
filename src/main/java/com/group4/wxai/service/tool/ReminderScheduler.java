package com.group4.wxai.service.tool;

import com.group4.wxai.service.wx.WxMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderScheduler {

    private final ReminderTool reminderTool;
    private final WxMessageService wxMessageService;

    @Scheduled(fixedDelayString = "${team-tools.reminder.check-interval-ms:30000}")
    public void sendDueReminders() {
        Instant now = Instant.now();
        for (ReminderTool.DueReminder reminder : reminderTool.due(now)) {
            try {
                if (wxMessageService.trySendTextMessage(
                        reminder.userId(), "⏰ 提醒：" + reminder.content())) {
                    reminderTool.markDelivered(reminder.userId(), reminder.id(), now);
                }
            } catch (Exception e) {
                log.warn("提醒发送失败: user={}, id={}", reminder.userId(), reminder.id(), e);
            }
        }
    }
}
