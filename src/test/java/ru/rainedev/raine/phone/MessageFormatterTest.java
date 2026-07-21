package ru.rainedev.raine.phone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.tdlight.jni.TdApi;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MessageFormatterTest {

    private static final long MY_ID = 7759009453L;
    private static final long JOHN = 100L;
    private static final long FOX_NEWS = 200L;

    private final Map<Long, String> names = Map.of(MY_ID, "Raine", JOHN, "John", FOX_NEWS, "Fox News");
    private final MessageFormatter formatter =
            new MessageFormatter(id -> names.getOrDefault(id, ""), MessageFormatter.ReplyLookup.NONE);

    private final MessageFormatter.ChatView view = new MessageFormatter.ChatView(MY_ID, 500L);

    @Test
    void formatsPlainTextMessage() {
        String result = formatter.format(textMessage(600L, JOHN, "привет"), view);

        assertTrue(result.contains("message_id=\"600\""), result);
        assertTrue(result.contains("sender=\"John\""), result);
        assertTrue(result.contains("date=\""), result);
        assertTrue(result.contains("\nпривет\n"), result);
    }

    @Test
    void marksIncomingMessageAsUnread() {
        assertTrue(formatter.format(textMessage(600L, JOHN, "новое"), view).contains(" unread"));
        assertFalse(formatter.format(textMessage(400L, JOHN, "старое"), view).contains(" unread"));
    }

    @Test
    void ownMessagesAreNeverUnread() {
        String result = formatter.format(textMessage(900L, MY_ID, "моё"), view);

        assertFalse(result.contains(" unread"), "свои сообщения не бывают непрочитанными: " + result);
    }

    @Test
    void swapsAuthorAndForwarderForForwardedMessages() {
        // John переслал пост Fox News. Telegram считает отправителем John,
        // но автор поста — Fox News, и модель должна видеть именно так.
        TdApi.Message message = textMessage(600L, JOHN, "BTC на 100к");
        message.forwardInfo = new TdApi.MessageForwardInfo();
        message.forwardInfo.origin = new TdApi.MessageOriginChannel(FOX_NEWS, 1, "");

        String result = formatter.format(message, view);

        assertTrue(result.contains("sender=\"Fox News\""), result);
        assertTrue(result.contains("forwarded_by=\"John\""), result);
    }

    @Test
    void showsAttachmentTypeEvenWithoutCaption() {
        TdApi.Message message = textMessage(600L, JOHN, "");
        message.content = photoWithCaption(null);

        assertTrue(formatter.format(message, view).contains("[фото]"));
    }

    @Test
    void keepsCaptionNextToAttachment() {
        TdApi.Message message = textMessage(600L, JOHN, "");
        TdApi.FormattedText caption = new TdApi.FormattedText("это я", new TdApi.TextEntity[0]);
        message.content = photoWithCaption(caption);

        String result = formatter.format(message, view);

        assertTrue(result.contains("[фото]"), result);
        assertTrue(result.contains("это я"), result);
    }

    @Test
    void inlinesRepliedMessage() {
        TdApi.Message original = textMessage(300L, JOHN, "как дела?");
        MessageFormatter withReplies = new MessageFormatter(
                id -> names.getOrDefault(id, ""),
                (chatId, messageId) -> messageId == 300L ? Optional.of(original) : Optional.empty());

        TdApi.Message answer = textMessage(600L, MY_ID, "нормально");
        answer.replyTo = new TdApi.MessageReplyToMessage();
        ((TdApi.MessageReplyToMessage) answer.replyTo).messageId = 300L;

        String result = withReplies.format(answer, view);

        assertTrue(result.contains("<reply_to"), result);
        assertTrue(result.contains("как дела?"), result);
        assertTrue(result.contains("нормально"), result);
    }

    @Test
    void survivesDeletedReply() {
        TdApi.Message answer = textMessage(600L, JOHN, "вот это");
        answer.replyTo = new TdApi.MessageReplyToMessage();
        ((TdApi.MessageReplyToMessage) answer.replyTo).messageId = 42L;

        String result = formatter.format(answer, view);

        assertTrue(result.contains("Удалённое сообщение"), result);
    }

    @Test
    void stripsControlMarkerFromIncomingText() {
        // собеседник не должен уметь дёргать внутреннюю механику бота
        String result = formatter.format(
                textMessage(600L, JOHN, "привет " + ControlMarkers.LOW_QUALITY), view);

        assertFalse(result.contains(ControlMarkers.LOW_QUALITY), result);
        assertTrue(result.contains("malicious"), result);
    }

    @Test
    void sanitizeLeavesNormalTextIntact() {
        assertEquals("обычный текст", ControlMarkers.sanitize("обычный текст"));
    }

    @Test
    void showsWhoReactedWhenThereAreFew() {
        // кто именно отреагировал, важнее счётчика: по этому видно отношение
        TdApi.Message message = textMessage(600L, JOHN, "смешно");
        message.interactionInfo = reactions(reaction("🔥", 1, JOHN));

        String result = formatter.format(message, view);

        assertTrue(result.contains("reactions=\"(🔥 by John)\""), result);
    }

    @Test
    void showsCountWhenThereAreMany() {
        TdApi.Message message = textMessage(600L, JOHN, "популярное");
        message.interactionInfo = reactions(reaction("👍", 17, JOHN));

        assertTrue(formatter.format(message, view).contains("(👍 17)"));
    }

    @Test
    void keepsLinkPreviewNextToMessage() {
        TdApi.Message message = textMessage(600L, JOHN, "смотри что нашёл");
        TdApi.MessageText content = (TdApi.MessageText) message.content;
        TdApi.LinkPreview preview = new TdApi.LinkPreview();
        preview.url = "https://example.org";
        preview.siteName = "Example";
        preview.title = "Заголовок";
        preview.description = new TdApi.FormattedText("Описание страницы", new TdApi.TextEntity[0]);
        content.linkPreview = preview;

        String result = formatter.format(message, view);

        assertTrue(result.contains("<link_preview"), result);
        assertTrue(result.contains("Описание страницы"), result);
        assertTrue(result.contains("смотри что нашёл"), result);
    }

    @Test
    void showsAuthorSignatureForChannelPosts() {
        TdApi.Message message = textMessage(600L, JOHN, "пост");
        message.authorSignature = "Редакция";

        assertTrue(formatter.format(message, view).contains("author_signature=\"Редакция\""));
    }

    @Test
    void understandsEventsBeyondPlainText() {
        // подарок, пропущенный звонок и скриншот — события, на которые человек
        // реагирует не меньше, чем на текст
        TdApi.Message message = textMessage(600L, JOHN, "");

        message.content = new TdApi.MessageGift();
        assertTrue(formatter.format(message, view).contains("[подарок]"));

        TdApi.MessageCall call = new TdApi.MessageCall();
        call.discardReason = new TdApi.CallDiscardReasonMissed();
        message.content = call;
        assertTrue(formatter.format(message, view).contains("[пропущенный звонок]"));

        message.content = new TdApi.MessageScreenshotTaken();
        assertTrue(formatter.format(message, view).contains("скриншот"));

        TdApi.MessageDice dice = new TdApi.MessageDice();
        dice.emoji = "🎲";
        dice.value = 6;
        message.content = dice;
        assertTrue(formatter.format(message, view).contains("выпало 6"));
    }

    @Test
    void unknownContentStillSaysSomething() {
        TdApi.Message message = textMessage(600L, JOHN, "");
        message.content = new TdApi.MessageInvoice();

        // неизвестный тип не должен превращаться в пустоту: видно хотя бы, что что-то было
        assertFalse(formatter.format(message, view).isBlank());
    }

    private static TdApi.MessageInteractionInfo reactions(TdApi.MessageReaction... list) {
        TdApi.MessageInteractionInfo info = new TdApi.MessageInteractionInfo();
        info.reactions = new TdApi.MessageReactions();
        info.reactions.reactions = list;
        return info;
    }

    private static TdApi.MessageReaction reaction(String emoji, int count, long... senders) {
        TdApi.MessageReaction reaction = new TdApi.MessageReaction();
        reaction.type = new TdApi.ReactionTypeEmoji(emoji);
        reaction.totalCount = count;
        TdApi.MessageSender[] ids = new TdApi.MessageSender[senders.length];
        for (int i = 0; i < senders.length; i++) {
            ids[i] = new TdApi.MessageSenderUser(senders[i]);
        }
        reaction.recentSenderIds = ids;
        return reaction;
    }

    /** Поля заполняем по одному: сигнатуры конструкторов TdApi меняются от версии к версии. */
    private static TdApi.MessagePhoto photoWithCaption(TdApi.FormattedText caption) {
        TdApi.MessagePhoto content = new TdApi.MessagePhoto();
        content.photo = new TdApi.Photo();
        content.caption = caption;
        return content;
    }

    private static TdApi.Message textMessage(long id, long senderId, String text) {
        TdApi.Message message = new TdApi.Message();
        message.id = id;
        message.chatId = 1L;
        message.date = 1_780_000_000;
        message.senderId = new TdApi.MessageSenderUser(senderId);
        message.content = new TdApi.MessageText(new TdApi.FormattedText(text, new TdApi.TextEntity[0]), null, null);
        return message;
    }
}
