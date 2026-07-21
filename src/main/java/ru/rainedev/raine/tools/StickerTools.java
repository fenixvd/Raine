package ru.rainedev.raine.tools;

import it.tdlight.jni.TdApi;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.core.Tool;
import ru.rainedev.raine.telegram.Telegram;
import ru.rainedev.raine.telegram.TelegramActions;
import ru.rainedev.raine.vision.TelegramMedia;

/**
 * Стикеры. Вынесены отдельно, потому что их три и они самостоятельны:
 * посмотреть свои, забрать понравившийся чужой, отправить.
 */
public final class StickerTools {

    private static final Logger log = LoggerFactory.getLogger(StickerTools.class);

    /** Столько стикеров помещается в избранном и недавних. */
    private static final int LIMIT = 40;

    private final Telegram telegram;
    private final TelegramActions actions;
    private TelegramMedia media;

    /** Отправлять стикер можно ровно туда же, куда и обычное сообщение. */
    private java.util.function.LongPredicate allowed = chatId -> true;

    public StickerTools(Telegram telegram, TelegramActions actions) {
        this.telegram = telegram;
        this.actions = actions;
    }

    public void allowedTo(java.util.function.LongPredicate allowed) {
        this.allowed = allowed;
    }

    public void media(TelegramMedia media) {
        this.media = media;
    }

    /**
     * Список своих стикеров с описанием каждого.
     * <p>
     * Одного эмодзи мало: по нему не выбрать, какой стикер уместен сейчас.
     * Описания берутся зрением и кэшируются, поэтому список стоит дорого
     * только в первый раз.
     */
    public Tool list() {
        return Tool.simple("sticker_list", "Returns your saved stickers with a description of each. "
                + "Use this before #sticker_send.", arguments -> {
                    List<TdApi.Sticker> stickers = telegram.savedStickers(LIMIT);
                    if (stickers.isEmpty()) {
                        return "You have no saved stickers yet.";
                    }
                    StringBuilder out = new StringBuilder("<stickers>\n");
                    for (TdApi.Sticker sticker : stickers) {
                        out.append("<sticker sticker_id=\"").append(sticker.id)
                                .append("\" emoji=\"").append(sticker.emoji == null ? "" : sticker.emoji)
                                .append("\">");
                        if (media != null) {
                            out.append('\n').append(media.describeSticker(sticker).strip()).append('\n');
                        }
                        out.append("</sticker>\n");
                    }
                    return out.append("</stickers>").toString();
                });
    }

    /** Понравившийся чужой стикер можно забрать себе. */
    public Tool save() {
        return Tool.named("sticker_save")
                .describedAs("Saves a sticker so you can use it later. Use this if you liked a sticker someone sent.")
                .requiredInteger("sticker_id", "Id of the sticker to save")
                .build(arguments -> {
                    long stickerId = ru.rainedev.raine.core.Numbers.longAt(arguments, "sticker_id", 0);
                    if (stickerId == 0) {
                        return "Provide the sticker_id you want to save.";
                    }
                    java.util.Optional<Telegram.StickerRef> sticker = telegram.sticker(stickerId);
                    if (sticker.isEmpty()) {
                        return "You have not seen that sticker. Use sticker_list, or save one you were sent.";
                    }
                    actions.saveSticker(sticker.get());
                    log.info("Стикер {} сохранён", stickerId);
                    // сохранить молча — странно: человек в такой момент говорит «о, забрала себе»
                    return "Sticker saved. Tell them you liked it and kept it.";
                });
    }

    /** Стикер вместо слов — иногда точнее любого текста. */
    public Tool send(long chatId) {
        return Tool.named("sticker_send")
                .describedAs("Sends one of your saved stickers. Call sticker_list first to see what you have.")
                .requiredInteger("sticker_id", "Id of the sticker to send")
                .optionalString("reply_to_message_id",
                        "Optional. Quote a specific message — a sticker in reply to something said earlier.")
                .build(arguments -> {
                    // круг общения проверяется и здесь: открыть чат — одно,
                    // а отправить в него — другое, и запрет должен работать в обоих
                    if (!allowed.test(chatId)) {
                        log.info("Стикер в чат {} вне круга общения не отправлен", chatId);
                        return "You cannot send anything to that chat.";
                    }
                    long stickerId = ru.rainedev.raine.core.Numbers.longAt(arguments, "sticker_id", 0);
                    boolean known = telegram.savedStickers(LIMIT).stream().anyMatch(s -> s.id == stickerId);
                    if (!known) {
                        return "You don't have this sticker. Call sticker_list to see your stickers.";
                    }
                    java.util.Optional<Telegram.StickerRef> sticker = telegram.sticker(stickerId);
                    if (sticker.isEmpty()) {
                        return "Cannot send that sticker. Call sticker_list first.";
                    }
                    Long replyTo = ru.rainedev.raine.core.Numbers.longAt(arguments, "reply_to_message_id")
                            .filter(id -> telegram.message(chatId, id).isPresent())
                            .orElse(null);
                    actions.typing(chatId);
                    actions.sendSticker(chatId, sticker.get(), replyTo);
                    log.info("Стикер {} отправлен в чат {}", stickerId, chatId);
                    return "Sticker sent.";
                });
    }
}
