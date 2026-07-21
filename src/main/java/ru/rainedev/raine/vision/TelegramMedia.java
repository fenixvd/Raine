package ru.rainedev.raine.vision;

import it.tdlight.jni.TdApi;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.phone.MediaDescriber;
import ru.rainedev.raine.telegram.Telegram;

/** Скачивает вложение из Telegram и передаёт его зрению. */
public final class TelegramMedia implements MediaDescriber {

    private final Telegram telegram;
    private final Vision vision;
    private final Supplier<List<Message>> context;

    public TelegramMedia(Telegram telegram, Vision vision, Supplier<List<Message>> context) {
        this.telegram = telegram;
        this.vision = vision;
        this.context = context;
    }

    @Override
    public String describe(TdApi.Message message) {
        if (message == null || message.content == null) {
            return "";
        }
        return switch (message.content) {
            case TdApi.MessagePhoto photo -> look(largest(photo.photo), Vision.Kind.PHOTO);
            case TdApi.MessageSticker sticker -> {
                // запоминаем: без этого её нельзя ни сохранить, ни отправить
                telegram.rememberSticker(sticker.sticker);
                yield look(fileOf(sticker.sticker.sticker), Vision.Kind.STICKER);
            }
            // у подарка есть вид и цена — без них «подарок» ничего не говорит
            case TdApi.MessageGift gift -> gift(gift);
            case TdApi.MessageVoiceNote ignored -> heard(message, "voice");
            case TdApi.MessageVideoNote videoNote ->
                    // в кружке есть и картинка, и речь — берём и то, и другое
                    watch(fileOf(videoNote.videoNote.video)) + heard(message, "video_note");
            case TdApi.MessageVideo video -> {
                String seen = watch(fileOf(video.video.video));
                yield seen.isBlank() && video.video.thumbnail != null
                        ? look(fileOf(video.video.thumbnail.file), Vision.Kind.VIDEO)
                        : seen;
            }
            case TdApi.MessageAnimation animation -> {
                String seen = watch(fileOf(animation.animation.animation));
                // гифки часто не поддаются разбору — тогда хватит и заставки
                yield seen.isBlank() && animation.animation.thumbnail != null
                        ? look(fileOf(animation.animation.thumbnail.file), Vision.Kind.VIDEO)
                        : seen;
            }
            default -> "";
        };
    }

    /**
     * Голосовое без расшифровки — потерянное сообщение: видно, что оно было,
     * а что сказали, неизвестно.
     */
    private String heard(TdApi.Message message, String tag) {
        return telegram.recognizeSpeech(message.chatId, message.id)
                .map(text -> "<%s transcript>\n%s\n</%s>\n".formatted(tag, text.strip(), tag))
                .orElse("");
    }

    /**
     * Видео описывается по нескольким кадрам сразу. Если формат не поддался
     * разбору, берём заготовленную Telegram миниатюру: один кадр лучше, чем ничего.
     */
    private String watch(Optional<Integer> fileId) {
        return fileId.flatMap(telegram::download)
                .flatMap(ru.rainedev.raine.vision.VideoFrames::filmstrip)
                .map(strip -> vision.describe(strip, Vision.Kind.VIDEO, context.get()))
                .orElse("");
    }

    private String gift(TdApi.MessageGift message) {
        StringBuilder out = new StringBuilder("<gift cost=\"")
                .append(message.gift == null ? 0 : message.gift.starCount).append(" stars\"");
        if (message.text != null && !message.text.text.isBlank()) {
            out.append(" text=\"").append(message.text.text).append('"');
        }
        String looks = message.gift == null ? "" : describeSticker(message.gift.sticker);
        return looks.isBlank()
                ? out.append(" />").toString()
                : out.append(">\n").append(looks.strip()).append("\n</gift>").toString();
    }

    /** Описание стикера по его файлу: без него в списке видно только эмодзи. */
    public String describeSticker(TdApi.Sticker sticker) {
        return sticker == null ? "" : look(fileOf(sticker.sticker), Vision.Kind.STICKER);
    }

    /** Аватарка чата — по ней понятно, с кем разговариваешь. */
    public String describeAvatar(TdApi.ChatPhoto photo) {
        if (photo == null || photo.sizes == null || photo.sizes.length == 0) {
            return "";
        }
        return look(fileOf(photo.sizes[photo.sizes.length - 1].photo), Vision.Kind.AVATAR);
    }

    private String look(Optional<Integer> fileId, Vision.Kind kind) {
        return fileId.flatMap(telegram::download)
                .map((Path file) -> vision.describe(file, kind, context.get()))
                .orElse("");
    }

    /** Берём самый крупный вариант: мелкий превью модель толком не разглядит. */
    private static Optional<Integer> largest(TdApi.Photo photo) {
        if (photo == null || photo.sizes == null || photo.sizes.length == 0) {
            return Optional.empty();
        }
        return fileOf(photo.sizes[photo.sizes.length - 1].photo);
    }

    private static Optional<Integer> fileOf(TdApi.File file) {
        return file == null ? Optional.empty() : Optional.of(file.id);
    }
}
