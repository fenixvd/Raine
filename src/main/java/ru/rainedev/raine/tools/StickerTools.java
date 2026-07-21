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

    public StickerTools(Telegram telegram, TelegramActions actions) {
        this.telegram = telegram;
        this.actions = actions;
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
                    long stickerId = arguments.path("sticker_id").asLong();
                    if (stickerId == 0) {
                        return "Provide the sticker_id you want to save.";
                    }
                    int file = telegram.stickerFile(stickerId);
                    if (file == 0) {
                        return "You have not seen that sticker. Use sticker_list, or save one you were sent.";
                    }
                    actions.saveSticker(file);
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
                .build(arguments -> {
                    long stickerId = arguments.path("sticker_id").asLong();
                    boolean known = telegram.savedStickers(LIMIT).stream().anyMatch(s -> s.id == stickerId);
                    if (!known) {
                        return "You don't have this sticker. Call sticker_list to see your stickers.";
                    }
                    int file = telegram.stickerFile(stickerId);
                    if (file == 0) {
                        return "Cannot send that sticker. Call sticker_list first.";
                    }
                    actions.typing(chatId);
                    actions.sendSticker(chatId, file, null);
                    log.info("Стикер {} отправлен в чат {}", stickerId, chatId);
                    return "Sticker sent.";
                });
    }
}
