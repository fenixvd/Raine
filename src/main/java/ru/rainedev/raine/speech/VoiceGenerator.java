package ru.rainedev.raine.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.config.Config;

/**
 * Записывает голосовое сообщение: синтезирует речь и упаковывает её в тот
 * формат, который Telegram принимает как голосовое, а не как файл.
 */
public final class VoiceGenerator {

    private static final Logger log = LoggerFactory.getLogger(VoiceGenerator.class);

    /** Кадр в 20 мс — обычный для речи размер. */
    private static final int FRAME_MILLIS = 20;

    private static final int CHANNELS = 1;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final Random random = new Random();

    private final Config.Voice config;
    private final Path directory;

    public VoiceGenerator(Config.Voice config, Path directory) {
        this.config = config;
        this.directory = directory;
    }

    public boolean isAvailable() {
        return config.enabled() && config.apiKey() != null && !config.apiKey().isBlank();
    }

    /** Готовое голосовое: файл и длительность, которую надо показать в Telegram. */
    public record Voice(Path file, int seconds) {}

    public Voice speak(String text) {
        byte[] pcm = synthesize(text);
        byte[] ogg = encode(pcm);

        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(System.currentTimeMillis() + ".ogg");
            Files.write(file, ogg);
            int seconds = Math.max(1, pcm.length / 2 / config.sampleRate());
            log.info("Записано голосовое на {} с: {}", seconds, file.getFileName());
            return new Voice(file, seconds);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сохранить голосовое", e);
        }
    }

    private byte[] synthesize(String text) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.model());
        body.put("voice", config.voice());
        body.put("input", text);
        body.put("response_format", "pcm");
        body.put("speed", config.speed());

        try {
            String url = config.baseUrl().endsWith("/") ? config.baseUrl() : config.baseUrl() + "/";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url + "audio/speech"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .timeout(Duration.ofMinutes(2))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Синтез речи отказал (%d)".formatted(response.statusCode()));
            }
            if (response.body().length < 2) {
                throw new IllegalStateException("Синтез вернул пустой звук");
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Синтез прерван", e);
        } catch (IOException e) {
            throw new IllegalStateException("Синтез недоступен: " + e.getMessage(), e);
        }
    }

    /** Синтез отдаёт сырой звук, Telegram принимает только OGG/Opus. */
    private byte[] encode(byte[] pcm) {
        int rate = config.sampleRate();
        int frameSamples = rate * FRAME_MILLIS / 1000;

        try {
            // Режим VOIP выглядит уместнее для речи, но в этой сборке кодека он на
            // 24 кГц выдаёт тишину: пакеты формируются, длина правдоподобная, звука нет.
            // Проверено раскодированием — см. OggOpusWriterTest.
            OpusEncoder encoder = new OpusEncoder(rate, CHANNELS, OpusApplication.OPUS_APPLICATION_AUDIO);
            encoder.setBitrate(config.bitrate());

            OggOpusWriter ogg = new OggOpusWriter(rate, CHANNELS, encoder.getLookahead(), random.nextInt());
            ogg.writeHeaders();

            short[] samples = toSamples(pcm);
            byte[] packet = new byte[4000];
            int samplesPerFrameAt48k = OggOpusWriter.granuleRate() * FRAME_MILLIS / 1000;

            for (int offset = 0; offset < samples.length; offset += frameSamples) {
                short[] frame = new short[frameSamples];
                int available = Math.min(frameSamples, samples.length - offset);
                System.arraycopy(samples, offset, frame, 0, available);
                // последний кадр добивается тишиной: кодек принимает только целые кадры

                int length = encoder.encode(frame, 0, frameSamples, packet, 0, packet.length);
                byte[] encoded = new byte[length];
                System.arraycopy(packet, 0, encoded, 0, length);
                ogg.writePacket(encoded, samplesPerFrameAt48k);
            }
            return ogg.finish();
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось закодировать голосовое: " + e.getMessage(), e);
        }
    }

    private static short[] toSamples(byte[] pcm) {
        ByteBuffer buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        short[] samples = new short[pcm.length / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = buffer.getShort();
        }
        return samples;
    }
}
