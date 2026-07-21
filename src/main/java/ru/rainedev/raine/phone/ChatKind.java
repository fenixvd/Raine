package ru.rainedev.raine.phone;

import it.tdlight.jni.TdApi;

/** Тип чата определяет, что Raine вообще может в нём делать. */
public enum ChatKind {
    /** Личная переписка. */
    DM,
    /** Групповой чат: читает всё, отвечает по делу или по упоминанию. */
    GROUP,
    /** Канал: только читает, реагирует и пересылает — отвечать физически нельзя. */
    CHANNEL;

    public static ChatKind of(TdApi.Chat chat) {
        return switch (chat.type) {
            case TdApi.ChatTypePrivate ignored -> DM;
            case TdApi.ChatTypeSecret ignored -> DM;
            case TdApi.ChatTypeSupergroup supergroup -> supergroup.isChannel ? CHANNEL : GROUP;
            case null, default -> GROUP;
        };
    }
}
