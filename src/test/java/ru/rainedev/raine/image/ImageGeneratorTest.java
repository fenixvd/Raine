package ru.rainedev.raine.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ImageGeneratorTest {

    @Test
    void dimensionsAreRoundedToWhatModelsAccept() {
        assertEquals(768, HordeClient.sanitize(800), "размер должен быть кратен 64");
        assertEquals(1024, HordeClient.sanitize(1400), "крупные размеры требуют запаса очков вперёд");
        assertEquals(512, HordeClient.sanitize(100), "слишком мелкие модели рисуют плохо");
        assertEquals(0, HordeClient.sanitize(900) % 64);
        assertEquals(0, HordeClient.sanitize(1023) % 64);
    }

    @Test
    void withoutKeyGenerationIsNotOffered() {
        // инструмент, которым нельзя воспользоваться, обещает собеседнику несбыточное
        assertFalse(new HordeClient("https://example.org/", "", java.util.List.of()).isAvailable());
        assertFalse(new HordeClient("https://example.org/", null, java.util.List.of()).isAvailable());
        assertTrue(new HordeClient("https://example.org/", "ключ", java.util.List.of()).isAvailable());
    }

    @Test
    void secondPhotoIsRefusedWhileTheFirstIsStillBeingTaken() throws Exception {
        // очередь бывает занята десятками минут; не дождавшись, она запускала бы
        // вторую работу поверх первой — оба задания стоят очков, а нужен один снимок
        ImageGenerator camera = new ImageGenerator(
                new HordeClient("http://127.0.0.1:9/", "ключ", java.util.List.of("модель")),
                null, null, java.nio.file.Path.of("/tmp"), "Raine", "модель", 1);

        java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);

        boolean first = camera.takeInBackground("селфи",
                photo -> { },
                reason -> {
                    try {
                        holding.await();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    finished.countDown();
                });

        assertTrue(first, "первая съёмка должна начаться");
        while (!camera.isBusy()) {
            Thread.sleep(5);
        }
        assertFalse(camera.takeInBackground("ещё селфи", photo -> { }, reason -> { }),
                "вторая съёмка поверх первой не запускается");

        holding.countDown();
        finished.await();
    }

    @Test
    void cameraIsFreeAgainAfterFailure() throws Exception {
        ImageGenerator camera = new ImageGenerator(
                new HordeClient("http://127.0.0.1:9/", "ключ", java.util.List.of("модель")),
                null, null, java.nio.file.Path.of("/tmp"), "Raine", "модель", 1);

        java.util.concurrent.CountDownLatch failed = new java.util.concurrent.CountDownLatch(1);
        camera.takeInBackground("селфи", photo -> { }, reason -> failed.countDown());
        failed.await();

        // неудача не должна запирать возможность снимать до перезапуска
        while (camera.isBusy()) {
            Thread.sleep(5);
        }
        assertTrue(camera.takeInBackground("ещё раз", photo -> { }, reason -> { }));
    }

    @Test
    void unreachableQueueFailsLoudlyInsteadOfHanging() {
        // порт 9 всегда отвергает соединение; важно, что вызов падает,
        // а не зависает — ошибку цикл покажет модели текстом
        HordeClient horde = new HordeClient("http://127.0.0.1:9/", "ключ", java.util.List.of("модель"));

        assertThrows(RuntimeException.class, () -> horde.generate("кошка", "", 768, 768, 5.0, 30));
    }
}
