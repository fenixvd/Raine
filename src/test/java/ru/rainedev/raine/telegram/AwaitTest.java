package ru.rainedev.raine.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class AwaitTest {

    @Test
    void answerComesThrough() {
        assertEquals("ответ", Await.reply(CompletableFuture.completedFuture("ответ"), "GetChat"));
    }

    @Test
    void silenceDoesNotLastForever() {
        // молчание в ответ на один запрос иначе останавливает её целиком:
        // она замирает посреди хода и не отвечает никому
        CompletableFuture<String> silence = new CompletableFuture<>();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> Await.reply(silence, "GetChatHistory"));

        assertTrue(failure.getMessage().contains("GetChatHistory"), failure.getMessage());
        assertTrue(silence.isCancelled(), "брошенный запрос не должен висеть дальше");
    }

    @Test
    void telegramErrorIsPassedOnAsIs() {
        CompletableFuture<String> failed = CompletableFuture.failedFuture(
                new IllegalStateException("400: CHAT_NOT_FOUND"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> Await.reply(failed, "GetChat"));

        assertEquals("400: CHAT_NOT_FOUND", failure.getMessage());
    }
}
