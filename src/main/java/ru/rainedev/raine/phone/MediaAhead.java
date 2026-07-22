package ru.rainedev.raine.phone;

import it.tdlight.jni.TdApi;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Разглядывает вложения заранее и сразу все.
 * <p>
 * По одному это выходит непозволительно долго: на снимок уходит секунд восемь,
 * и десяток картинок в истории — это полторы минуты, пока собеседник смотрит
 * на непрочитанное сообщение. Смотреть их можно одновременно: обращения идут
 * в сеть и друг друга не ждут. Описания оседают в кэше, поэтому сама разметка
 * потом собирается мгновенно.
 */
public final class MediaAhead {

    private static final Logger log = LoggerFactory.getLogger(MediaAhead.class);

    /** Больше — уже не помощь, а очередь на стороне поставщика. */
    private static final int AT_ONCE = 6;

    private MediaAhead() {}

    public static void look(MediaDescriber media, List<TdApi.Message> messages) {
        if (media == MediaDescriber.NONE || messages.size() < 2) {
            return;
        }
        long startedAt = System.currentTimeMillis();
        try (ExecutorService pool = Executors.newFixedThreadPool(AT_ONCE,
                Thread.ofVirtual().name("raine-look-", 0).factory())) {
            List<Callable<String>> work = messages.stream()
                    .map(message -> (Callable<String>) () -> media.describe(message))
                    .toList();
            pool.invokeAll(work);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        log.debug("Вложения разобраны за {} мс", System.currentTimeMillis() - startedAt);
    }
}
