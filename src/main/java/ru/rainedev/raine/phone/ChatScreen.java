package ru.rainedev.raine.phone;

import it.tdlight.jni.TdApi;
import java.util.List;
import ru.rainedev.raine.prompt.Prompts;

/**
 * «Экран открытого чата»: то, что Raine видит, открыв переписку.
 * Шапка, сами сообщения и указания, как себя вести в чате этого типа.
 */
public final class ChatScreen {

    private final MessageFormatter formatter;
    private final Prompts prompts;

    /** Приписки к сообщениям истории — подключаются снаружи. */
    private final List<HistoryNote> notes = new java.util.ArrayList<>();

    /** У скольких последних сообщений разглядывать вложения. */
    private int recentDepth = 8;

    public ChatScreen recentDepth(int depth) {
        this.recentDepth = depth;
        return this;
    }

    public MessageFormatter formatter() {
        return formatter;
    }

    public ChatScreen(MessageFormatter formatter, Prompts prompts) {
        this.formatter = formatter;
        this.prompts = prompts;
    }

    public ChatScreen note(HistoryNote note) {
        notes.add(note);
        return this;
    }

    /**
     * @param text текст для модели
     * @param kind тип чата — по нему вызывающий решает, какие инструменты выдать
     */
    public record Screen(String text, ChatKind kind) {}

    /** @param messages от старых к новым — порядок чтения переписки */
    public Screen render(TdApi.Chat chat, List<TdApi.Message> messages, MessageFormatter.ChatView view) {
        ChatKind kind = ChatKind.of(chat);

        StringBuilder text = new StringBuilder(
                "You switched to the chat \"%s\" in Telegram. You see last messages:\n".formatted(chat.title));

        if (messages.isEmpty()) {
            text.append("This chat is empty! Only proceed if you looked up a @username and it led you here.\n");
        }

        // вложения свежих сообщений разглядываются заранее и все сразу: по одному
        // это минута молчания, пока собеседник смотрит на непрочитанное
        int from = Math.max(0, messages.size() - recentDepth);
        List<TdApi.Message> recent = messages.subList(from, messages.size());
        MediaAhead.look(formatter.media(), recent);

        for (TdApi.Message message : messages) {
            String rendered = recent.contains(message)
                    ? formatter.format(message, view)
                    : formatter.formatBriefly(message, view);
            for (HistoryNote note : notes) {
                rendered = note.addTo(chat, message, rendered);
            }
            text.append(rendered);
        }

        text.append(instructions(chat, kind));
        return new Screen(text.toString(), kind);
    }

    private String instructions(TdApi.Chat chat, ChatKind kind) {
        return switch (kind) {
            case DM -> """

                    <instructions>
                    You are in private chat with %s (also known as direct messages or DM).

                    %s
                    </instructions>
                    """.formatted(chat.title, prompts.load("messages_epilogue.md"));
            case GROUP -> """

                    <instructions>
                    You are in group chat called "%s".

                    %s
                    </instructions>
                    """.formatted(chat.title, prompts.load("messages_epilogue.md"));
            case CHANNEL -> """

                    <instructions>
                    You are in telegram channel (also known as supergroup) called "%s".
                    Pay close attention to these messages. Acquire context from them. You can't respond in telegram \
                    channels (#send_telegram_message tool is not available). Instead, do what you usually do when \
                    reading newsletters: reflect and reason on them.
                    Some channels have reactions enabled. In that case, you can sometimes react with \
                    #react_with_emoji to express your feelings about a message, but you can't send a full reply.

                    Forwarding posts:
                    If you find a post genuinely interesting, funny, or relevant to someone you know — you can forward \
                    it to another chat using #forward_message. Be selective: only forward posts that are truly worth \
                    sharing. You can add a short comment expressing your reaction. Use #get_telegram_chats to find the \
                    destination chat_id if needed.
                    Do NOT forward ads, sponsored posts, or low-value content.
                    </instructions>
                    """.formatted(chat.title);
        };
    }
}
