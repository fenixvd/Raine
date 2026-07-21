package ru.rainedev.raine.phone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.Test;

class MediaInMessageTest {

    private static final long JOHN = 100L;
    private final MessageFormatter.ChatView view = new MessageFormatter.ChatView(1L, 0L);

    private static TdApi.Message voiceMessage() {
        TdApi.Message message = new TdApi.Message();
        message.id = 600L;
        message.chatId = 5L;
        message.date = 1_780_000_000;
        message.senderId = new TdApi.MessageSenderUser(JOHN);
        TdApi.MessageVoiceNote content = new TdApi.MessageVoiceNote();
        content.voiceNote = new TdApi.VoiceNote();
        message.content = content;
        return message;
    }

    @Test
    void transcriptAppearsUnderTheAttachment() {
        MessageFormatter formatter = new MessageFormatter(id -> "John", MessageFormatter.ReplyLookup.NONE,
                message -> "[voice transcription]: привет, как ты там\n");

        String result = formatter.format(voiceMessage(), view);

        assertTrue(result.contains("[голосовое]"), "тип вложения остаётся видимым: " + result);
        assertTrue(result.contains("привет, как ты там"), result);
    }

    @Test
    void withoutRecognitionOnlyTheTypeIsVisible() {
        MessageFormatter formatter = new MessageFormatter(id -> "John", MessageFormatter.ReplyLookup.NONE,
                message -> "");

        String result = formatter.format(voiceMessage(), view);

        assertTrue(result.contains("[голосовое]"), result);
    }

    @Test
    void transcriptIsCleanedFromControlMarkers() {
        // расшифровка — это текст собеседника, он мог наговорить что угодно
        MessageFormatter formatter = new MessageFormatter(id -> "John", MessageFormatter.ReplyLookup.NONE,
                message -> "расшифровка " + ControlMarkers.LOW_QUALITY);

        String result = formatter.format(voiceMessage(), view);

        assertFalse(result.contains(ControlMarkers.LOW_QUALITY), result);
    }
}
