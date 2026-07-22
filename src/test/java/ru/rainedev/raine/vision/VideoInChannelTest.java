package ru.rainedev.raine.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.tdlight.jni.TdApi;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class VideoInChannelTest {

    @Test
    void inChannelTheVideoItselfIsNotTouched() {
        // покадровый разбор с расшифровкой звука стоит там дороже, чем весь
        // остальной день переписки, а понять пост хватает и заставки
        AtomicBoolean watched = new AtomicBoolean();

        String described = TelegramMedia.watchOrGlance(true,
                () -> {
                    watched.set(true);
                    return "покадровое описание";
                },
                () -> "заставка");

        assertEquals("заставка", described);
        assertFalse(watched.get(), "к самому ролику в канале даже прикасаться незачем");
    }

    @Test
    void inConversationTheVideoIsWatchedWhole() {
        assertEquals("покадровое описание",
                TelegramMedia.watchOrGlance(false, () -> "покадровое описание", () -> "заставка"));
    }

    @Test
    void whenTheFormatIsUnknownTheThumbnailSaves() {
        assertEquals("заставка", TelegramMedia.watchOrGlance(false, () -> "", () -> "заставка"));
    }

    @Test
    void whenNothingWorkedItSaysSoInsteadOfStayingSilent() {
        // пустота в разметке выглядит так, будто вложения не было вовсе
        String described = TelegramMedia.watchOrGlance(false, () -> "", () -> "");

        assertTrue(described.contains("not supported"), described);
    }

    @Test
    void videoSentAsAFileIsRecognisedByItsName() {
        assertEquals(Vision.Kind.VIDEO, TelegramMedia.kindOf(document("Обзор.MP4")));
        assertEquals(Vision.Kind.VIDEO, TelegramMedia.kindOf(document("clip.webm")));
        assertEquals(Vision.Kind.PHOTO, TelegramMedia.kindOf(document("снимок.jpg")));
        assertEquals(Vision.Kind.STICKER, TelegramMedia.kindOf(document("отчёт.pdf")));
        assertEquals(Vision.Kind.STICKER, TelegramMedia.kindOf(document(null)));
    }

    private static TdApi.MessageDocument document(String fileName) {
        TdApi.MessageDocument message = new TdApi.MessageDocument();
        message.document = new TdApi.Document();
        message.document.fileName = fileName;
        return message;
    }
}
