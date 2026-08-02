package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * За месяц она одиннадцать раз пробовала открыть чат по числу, которое чатом
 * не было — чаще всего по message_id, они в разметке выглядят такими же
 * длинными числами. Всё, что она получала в ответ, — «400: Chat not found»,
 * и по этому ответу нельзя понять, что делать дальше.
 */
class NoSuchChatTest {

    @Test
    void refusalSaysWhereToTakeTheRealId() {
        String answer = TelegramTools.noSuchChat(4329570304L);

        assertTrue(answer.contains("search_chats"), answer);
        assertTrue(answer.contains("get_telegram_chats"), answer);
    }

    @Test
    void refusalWarnsAboutTheUsualMistake() {
        String answer = TelegramTools.noSuchChat(4329570304L);

        assertTrue(answer.contains("message_id"), "самая частая путаница должна быть названа: " + answer);
    }

    @Test
    void refusalRepeatsTheIdThatFailed() {
        String answer = TelegramTools.noSuchChat(1157431879L);

        assertTrue(answer.contains("1157431879"), answer);
        assertFalse(answer.contains("Chat not found"),
                "ответ должен объяснять, а не пересказывать ошибку Telegram: " + answer);
    }
}
