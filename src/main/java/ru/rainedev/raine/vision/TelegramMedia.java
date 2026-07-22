package ru.rainedev.raine.vision;

import it.tdlight.jni.TdApi;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.phone.ChatKind;
import ru.rainedev.raine.phone.MediaDescriber;
import ru.rainedev.raine.telegram.Telegram;

/** Скачивает вложение из Telegram и передаёт его зрению. */
public final class TelegramMedia implements MediaDescriber {

    private final Telegram telegram;
    private final Vision vision;
    private final Supplier<List<Message>> context;

    /** Разбирать ли ролики в каналах покадрово. Обычно нет — слишком дорого. */
    private boolean videoInChannels;

    public TelegramMedia(Telegram telegram, Vision vision, Supplier<List<Message>> context) {
        this.telegram = telegram;
        this.vision = vision;
        this.context = context;
    }

    public void videoInChannels(boolean allowed) {
        this.videoInChannels = allowed;
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
            case TdApi.MessageVideo video -> video.video == null ? "" : watchOrGlance(
                    message, fileOf(video.video.video), thumbnailOf(video.video.thumbnail));
            case TdApi.MessageAnimation animation -> animation.animation == null ? "" : watchOrGlance(
                    message, fileOf(animation.animation.animation), thumbnailOf(animation.animation.thumbnail));
            // роликом, присланным файлом, каналы пользуются чаще всего: по типу
            // сообщения это документ, и без разбора имени он уходил бы в разбор
            // картинки, где из видео не видно ничего
            case TdApi.MessageDocument document -> document.document == null ? "" : switch (kindOf(document)) {
                case VIDEO -> watchOrGlance(message, fileOf(document.document.document),
                        thumbnailOf(document.document.thumbnail));
                case PHOTO -> look(fileOf(document.document.document), Vision.Kind.PHOTO);
                default -> "";
            };
            default -> "";
        };
    }

    /**
     * Ролик смотрится либо целиком, либо одним взглядом на заставку.
     * <p>
     * В ленте канала посты пролистывают, и покадровый разбор с расшифровкой
     * звука обходится там дороже, чем весь остальной день переписки: каждый
     * кадр — отдельное обращение к зрению, и идут они друг за другом. Понять
     * пост хватает и заставки. В личной переписке наоборот: ролик там часть
     * разговора, и его смотрят целиком.
     */
    private String watchOrGlance(TdApi.Message message, Optional<Integer> video, Optional<Integer> thumbnail) {
        return watchOrGlance(inChannel(message) && !videoInChannels,
                () -> watch(video),
                () -> thumbnail.map(file -> look(Optional.of(file), Vision.Kind.VIDEO)).orElse(""));
    }

    /**
     * @param onlyGlance смотреть лишь на заставку, не трогая сам ролик
     */
    static String watchOrGlance(boolean onlyGlance, java.util.function.Supplier<String> watch,
                                java.util.function.Supplier<String> glance) {
        if (onlyGlance) {
            return glance.get();
        }
        String seen = watch.get();
        if (!seen.isBlank()) {
            return seen;
        }
        // формат не поддался разбору — хотя бы заставка
        String glanced = glance.get();
        return glanced.isBlank()
                // пустота в разметке выглядит так, будто вложения не было вовсе
                ? "<video description>\nThis media type is not supported\n</video>\n"
                : glanced;
    }

    private boolean inChannel(TdApi.Message message) {
        try {
            return ChatKind.of(telegram.chatCached(message.chatId)) == ChatKind.CHANNEL;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Optional<Integer> thumbnailOf(TdApi.Thumbnail thumbnail) {
        return thumbnail == null ? Optional.empty() : fileOf(thumbnail.file);
    }

    /** Расширения, по которым видно, что внутри файла. */
    private static final List<String> VIDEO_EXTENSIONS =
            List.of(".mp4", ".mov", ".mkv", ".webm", ".avi", ".gif", ".m4v", ".3gp");
    private static final List<String> IMAGE_EXTENSIONS =
            List.of(".jpg", ".jpeg", ".png", ".webp", ".bmp");

    static Vision.Kind kindOf(TdApi.MessageDocument document) {
        String name = document.document.fileName == null ? "" : document.document.fileName.toLowerCase();
        if (VIDEO_EXTENSIONS.stream().anyMatch(name::endsWith)) {
            return Vision.Kind.VIDEO;
        }
        if (IMAGE_EXTENSIONS.stream().anyMatch(name::endsWith)) {
            return Vision.Kind.PHOTO;
        }
        return Vision.Kind.STICKER;   // ни то ни другое — смотреть нечего
    }

    /**
     * Голосовое без расшифровки — потерянное сообщение: видно, что оно было,
     * а что сказали, неизвестно.
     */
    private String heard(TdApi.Message message, String tag) {
        return telegram.recognizeSpeech(message.chatId, message.id)
                // подписью, а не тегом: расшифровка лежит внутри разметки самого
                // сообщения, и лишний тег там путается с её собственными
                .map(text -> "[%s transcription]: %s\n".formatted(tag, text.strip()))
                .orElse("");
    }

    /**
     * Видео описывается покадрово, с отметками времени. Если формат не поддался
     * разбору, зовущий берёт заготовленную Telegram миниатюру: один кадр лучше,
     * чем ничего.
     */
    private String watch(Optional<Integer> fileId) {
        return fileId.flatMap(telegram::download)
                .map(file -> vision.describeVideo(VideoFrames.sample(file), context.get(), file))
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
