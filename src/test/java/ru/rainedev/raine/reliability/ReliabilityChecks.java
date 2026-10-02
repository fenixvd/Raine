package ru.rainedev.raine.reliability;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import ru.rainedev.raine.config.*;
import ru.rainedev.raine.core.*;
import ru.rainedev.raine.llm.*;
import ru.rainedev.raine.memory.*;
import ru.rainedev.raine.storage.AtomicFiles;

/** Offline failure-injection scenarios, also runnable when JUnit is unavailable. */
public final class ReliabilityChecks {
    private ReliabilityChecks() {}
    private static final class Fake implements LlmClient {
        java.util.function.Function<List<Message>, ChatResponse> respond = h -> response("wait");
        java.util.function.Function<String, double[]> embed = s -> new double[] {1, 0};
        int chats;
        int embeddings;
        List<Message> seen = List.of();
        @Override public ChatResponse chat(String s, List<Message> h, JsonNode t) {
            chats++; seen = List.copyOf(h); return respond.apply(h);
        }
        @Override public double[] embedding(String s) { embeddings++; return embed.apply(s); }
    }
    private static ToolCall call(String name) {
        return new ToolCall("call_" + name, "function", new ToolCall.Function(name, "{}"));
    }
    private static ChatResponse response(String... actions) {
        return answer("", Arrays.stream(actions).map(ReliabilityChecks::call).toList(), 10);
    }
    private static ChatResponse answer(String text, List<ToolCall> calls, long tokens) {
        return new ChatResponse("id", "test", List.of(new ChatResponse.Choice(0,
                new Message(Message.Role.ASSISTANT, text, calls, null, null), "stop")),
                new ChatResponse.Usage(tokens, 0, tokens));
    }
    private static void check(boolean condition, String failure) {
        if (!condition) throw new AssertionError(failure);
    }
    private static Path temporary() throws IOException { return Files.createTempDirectory("raine-reliability-"); }
    private static Diary diary(Path path) {
        Diary diary = new Diary(path);
        diary.save("Важное воспоминание, которое нельзя потерять при сетевом сбое.", new double[] {1, 0});
        return diary;
    }
    private static SleepConsolidation consolidation(Diary d, Fake f, int requests, long tokens) {
        return new SleepConsolidation(d, f, () -> "test", d.directory().resolve("archive"),
                4000, new Random(1), requests, tokens);
    }
    public static void failedEmbeddingPreservesOriginals() throws Exception {
        Path dir = temporary(); Diary d = diary(dir); Fake f = new Fake();
        f.respond = h -> answer("Переписанное воспоминание достаточной длины.", List.of(), 10);
        f.embed = s -> { throw new IllegalStateException("offline"); };
        consolidation(d, f, 20, 1000).run(Duration.ofSeconds(1), () -> false);
        check(new Diary(dir).size() == 1, "original memory disappeared after embedding failure");
        check(!Files.exists(dir.resolve("archive")), "original was archived without replacement");
    }
    public static void partialEmbeddingFailureWritesNothing() throws Exception {
        Path dir = temporary(); Diary d = diary(dir); Fake f = new Fake();
        f.respond = h -> answer("Первое новое воспоминание.\n---\nВторое новое воспоминание.", List.of(), 10);
        f.embed = s -> { if (f.embeddings == 2) throw new IllegalStateException("offline"); return new double[] {1, 0}; };
        consolidation(d, f, 20, 1000).run(Duration.ofSeconds(1), () -> false);
        check(new Diary(dir).size() == 1, "partial replacements were committed");
    }
    public static void malformedReplacementDoesNotForget() throws Exception {
        for (String text : List.of("ок", "{broken}\nДостаточно длинный текст.", "{\"confidence\":\"bad\"}\nДлинный текст")) {
            Path dir = temporary(); Diary d = diary(dir); Fake f = new Fake();
            f.respond = h -> answer(text, List.of(), 10);
            consolidation(d, f, 20, 1000).run(Duration.ofSeconds(1), () -> false);
            check(new Diary(dir).size() == 1, "invalid model response archived the original");
        }
    }
    public static void explicitForgetAndSuccessfulMerge() throws Exception {
        Path dir = temporary(); Diary d = diary(dir); Fake f = new Fake();
        f.respond = h -> answer("{\"confidence\":-1}\nЗабыть недостоверное воспоминание", List.of(), 10);
        consolidation(d, f, 20, 1000).run(Duration.ofSeconds(1), () -> false);
        check(new Diary(dir).isEmpty(), "explicit forgetting no longer works");
        Path other = temporary(); Diary original = diary(other); Fake merge = new Fake();
        merge.respond = h -> answer("{\"confidence\":0.5}\nУточнённое воспоминание достаточной длины.", List.of(), 10);
        consolidation(original, merge, 20, 1000).run(Duration.ofSeconds(1), () -> false);
        check(new Diary(other).size() == 1 && merge.chats == 1, "successful replacement must be one pass");
    }
    public static void requestAndTokenLimitsPreserveOriginals() throws Exception {
        for (int kind = 0; kind < 2; kind++) {
            Path dir = temporary(); Diary d = diary(dir); Fake f = new Fake();
            f.respond = h -> answer("Новая запись достаточной длины для сохранения.", List.of(), 100);
            consolidation(d, f, kind == 0 ? 1 : 20, kind == 1 ? 50 : 1000)
                    .run(Duration.ofSeconds(1), () -> false);
            check(f.chats == 1 && f.embeddings == 0, "request/token threshold did not stop requests");
            check(new Diary(dir).size() == 1, "budget exhaustion removed original");
        }
    }
    public static void wakeDuringRequestPreservesOriginals() throws Exception {
        Path dir = temporary(); Diary d = diary(dir); Fake f = new Fake(); AtomicBoolean woke = new AtomicBoolean();
        f.respond = h -> { woke.set(true); return answer("Новая запись достаточной длины.", List.of(), 10); };
        consolidation(d, f, 20, 1000).run(Duration.ofSeconds(1), woke::get);
        check(f.embeddings == 0 && new Diary(dir).size() == 1, "wake-up ignored during consolidation");
    }
    public static void partialToolsKeepProtocolAndSkipRemaining() throws Exception {
        AtomicInteger sent = new AtomicInteger(); AtomicInteger skipped = new AtomicInteger(); Fake f = new Fake();
        f.respond = h -> f.chats == 1 ? response("send", "reject", "later", "wait") : response("wait");
        Toolbox tools = new Toolbox(
                Tool.simple("send", "send", a -> { sent.incrementAndGet(); return "sent"; }),
                Tool.simple("reject", "reject", a -> { throw new LowQualityException("too long"); }),
                Tool.simple("later", "later", a -> { skipped.incrementAndGet(); return "done"; }));
        NotificationLoop loop = new NotificationLoop(f, () -> "test", tools, 40000);
        loop.process(new Notification("hello"));
        check(sent.get() == 1 && skipped.get() == 0 && f.chats == 2, "partial batch repeated or finished incorrectly");
        long results = loop.context().stream().filter(m -> m.role() == Message.Role.TOOL).count();
        check(results == 5, "tool-call/result protocol is incomplete");
        check(f.seen.stream().anyMatch(m -> m.content() != null && m.content().contains("sent")), "sent result lost");
    }
    public static void networkResumeDoesNotReopenOrResend() throws Exception {
        Fake f = new Fake(); AtomicInteger sent = new AtomicInteger(); AtomicInteger opened = new AtomicInteger();
        f.respond = h -> {
            if (f.chats == 1) return response("send");
            if (f.chats == 2) throw new UncheckedIOException(new IOException("network"));
            return response("wait");
        };
        Tool send = Tool.simple("send", "send", a -> { sent.incrementAndGet(); return "sent exactly once"; });
        Tool open = Tool.named("open").buildContextual((a, added) -> { opened.incrementAndGet(); added.accept(send); return "chat opened"; });
        NotificationLoop loop = new NotificationLoop(f, () -> "test", new Toolbox(), 40000);
        Notification n = new Notification("hello", new Toolbox(open));
        try { loop.process(n); throw new AssertionError("expected failure"); } catch (UncheckedIOException expected) { }
        loop.process(n);
        check(opened.get() == 1 && sent.get() == 1, "resumption reexecuted actions");
        check(f.seen.stream().anyMatch(m -> m.content() != null && m.content().contains("sent exactly once")), "resumption forgot successful send");
        check(f.seen.stream().filter(m -> m.role() == Message.Role.USER).count() == 1, "notification duplicated");
    }
    public static void restUsesOriginalDeadline() throws Exception {
        Rest rest = new Rest(new Random(1));
        rest.night(new NightSleep(LocalTime.of(0, 0), LocalTime.of(0, 1), LocalTime.of(23, 58),
                LocalTime.of(23, 59), Clock.fixed(Instant.parse("2026-07-22T01:00:00Z"), ZoneOffset.UTC), new Random(1)));
        rest.duringNight((duration, woke) -> { try { Thread.sleep(600); } catch (InterruptedException e) { throw new RuntimeException(e); } });
        var sleep = Rest.class.getDeclaredMethod("sleep", Duration.class, String.class, boolean.class);
        sleep.setAccessible(true);
        long start = System.nanoTime(); sleep.invoke(rest, Duration.ofMillis(500), "test", true);
        check(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1000, "consolidation extended sleep");
        check(!rest.isResting(), "rest state stuck");
    }
    public static void stopInterruptsConsolidationPredicate() throws Exception {
        Rest rest = new Rest(new Random(1)); AtomicBoolean observed = new AtomicBoolean();
        rest.night(new NightSleep(LocalTime.of(0, 0), LocalTime.of(0, 1), LocalTime.of(23, 58),
                LocalTime.of(23, 59), Clock.fixed(Instant.parse("2026-07-22T01:00:00Z"), ZoneOffset.UTC), new Random(1)));
        rest.duringNight((d, wake) -> { rest.stop(); observed.set(wake.getAsBoolean()); });
        rest.maybeRest(); check(observed.get(), "shutdown invisible to consolidation");
    }
    public static void pendingAndAtomicFilesRemainReadable() throws Exception {
        Path dir = temporary(); Path file = dir.resolve("memory.md");
        AtomicFiles.writeString(file, "old"); AtomicFiles.writeString(file, "new");
        check(Files.readString(file).equals("new"), "atomic replacement failed");
        check(Files.list(dir).count() == 1, "temporary files leaked");
        PendingConversations pending = new PendingConversations(dir.resolve("pending"));
        for (int i = 0; i < 30; i++) pending.keep(List.of(Message.user("conversation " + i)));
        check(pending.waiting().size() == 30, "pending filenames collided");
        pending.keepFailure("notification", List.of(Message.user("actual context")), "offline");
        check(pending.waiting().size() == 30, "failed notification entered automatic replay");
        Path failed = Files.list(dir.resolve("pending/failed-notifications")).findFirst().orElseThrow();
        check(Files.readString(failed).contains("actual context"), "failed notification context missing");
    }
    public static void strictDiarySaveRetainsFailureSignal() throws Exception {
        Diary d = diary(temporary()); Fake f = new Fake();
        f.respond = h -> answer("", List.of(), 0);
        DiaryWriter writer = new DiaryWriter(d, f, "test", 0.97);
        try { writer.saveStrict("test", List.of(Message.user("hello"))); throw new AssertionError("empty summary reported success"); }
        catch (IllegalStateException expected) { }
        f.respond = h -> answer("Достаточно длинная запись для дневника.", List.of(), 10);
        f.embed = s -> { throw new IllegalStateException("offline"); };
        try { writer.saveStrict("test", List.of(Message.user("hello"))); throw new AssertionError("embedding failure reported success"); }
        catch (IllegalStateException expected) { }
    }
    public static void reloadShowsRestartOnlyChanges() throws Exception {
        Path file = temporary().resolve("config.properties");
        String base = "api_id=1\napi_hash=hash\nphone=+70000000000\nllm_base_url=http://example\nllm_model=old\nllm_api_key=key\n";
        Files.writeString(file, base); Config initial = Config.load(file);
        try (LiveConfig live = new LiveConfig(file, initial)) {
            Files.writeString(file, Files.readString(file).replace("llm_model=old", "llm_model=new").replace("sleep_consolidation=false", "sleep_consolidation=true"));
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2000));
            var checkOnce = LiveConfig.class.getDeclaredMethod("checkOnce"); checkOnce.setAccessible(true); checkOnce.invoke(live);
            check(live.restartRequired().equals(List.of("llm")), "restart-only fields were not identified");
            check(live.current().sleepConsolidation(), "hot setting not picked up");
        }
    }
    public static void httpErrorsAreClassified() {
        check(new LlmFailure(429, "rate limit").retryable(), "429 not retryable");
        check(new LlmFailure(503, "unavailable").retryable(), "503 not retryable");
        check(new LlmFailure(408, "timeout").retryable(), "408 not retryable");
        check(!new LlmFailure(400, "bad request").retryable(), "400 retried");
        check(new LlmFailure(402, "payment").requiresOperator(), "402 not suspended");
    }
    private static void fastRetry(NotificationLoop loop) throws Exception {
        var retryPause = NotificationLoop.class.getDeclaredMethod("retryPause", Duration.class);
        retryPause.setAccessible(true); retryPause.invoke(loop, Duration.ofMillis(10));
        var idle = NotificationLoop.class.getDeclaredMethod("idleCheck", Duration.class);
        idle.setAccessible(true); idle.invoke(loop, Duration.ofMillis(10));
    }
    public static void retryIsNotPreemptedByPriorityNotification() throws Exception {
        Fake f = new Fake(); AtomicInteger sent = new AtomicInteger();
        NotificationLoop loop = new NotificationLoop(f, () -> "test",
                new Toolbox(Tool.simple("send", "send", a -> { sent.incrementAndGet(); return "sent"; })), 40000);
        fastRetry(loop);
        f.respond = history -> {
            if (f.chats == 1) return response("send");
            if (f.chats == 2) {
                loop.submitFirst(new Notification("priority message"));
                throw new UncheckedIOException(new IOException("offline"));
            }
            if (f.chats == 3) check(history.stream().noneMatch(m -> m.content() != null && m.content().contains("priority message")),
                    "priority notification replaced interrupted turn");
            return response("wait");
        };
        CountDownLatch done = new CountDownLatch(2); loop.onNotificationDone(n -> done.countDown());
        Thread thread = Thread.ofVirtual().start(loop::run);
        try {
            loop.submit(new Notification("original message"));
            check(done.await(3, TimeUnit.SECONDS), "notification loop did not resume both turns");
            check(f.chats == 4 && sent.get() == 1, "turn was restarted rather than resumed");
        } finally { loop.stop(); thread.join(Duration.ofSeconds(2)); }
    }
    public static void exhaustedRetriesAreReportedOnce() throws Exception {
        Fake f = new Fake(); f.respond = h -> { throw new UncheckedIOException(new IOException("offline")); };
        NotificationLoop loop = new NotificationLoop(f, () -> "test", new Toolbox(), 40000); fastRetry(loop);
        AtomicInteger failures = new AtomicInteger(); CountDownLatch saved = new CountDownLatch(1);
        loop.onNotificationFailed((n, e) -> { failures.incrementAndGet(); saved.countDown(); });
        Thread thread = Thread.ofVirtual().start(loop::run);
        try {
            loop.submit(new Notification("must not disappear"));
            check(saved.await(3, TimeUnit.SECONDS), "exhausted notification was not retained");
            check(f.chats == 6 && failures.get() == 1 && loop.queued() == 0, "retry exhaustion spun or lost failure callback");
        } finally { loop.stop(); thread.join(Duration.ofSeconds(2)); }
    }
    public static void paymentSuspendsLaterNotifications() throws Exception {
        Fake f = new Fake(); f.respond = h -> { throw new LlmFailure(402, "no balance"); };
        NotificationLoop loop = new NotificationLoop(f, () -> "test", new Toolbox(), 40000); fastRetry(loop);
        CountDownLatch saved = new CountDownLatch(2); loop.onNotificationFailed((n, e) -> saved.countDown());
        Thread thread = Thread.ofVirtual().start(loop::run);
        try {
            loop.submit(new Notification("first")); loop.submit(new Notification("second"));
            check(saved.await(3, TimeUnit.SECONDS), "suspended notifications were not saved");
            check(f.chats == 1, "payment failure did not suspend model calls");
        } finally { loop.stop(); thread.join(Duration.ofSeconds(2)); }
    }
    public static void concurrentDiaryWritesHaveUniqueIds() throws Exception {
        Path dir = temporary(); Diary d = new Diary(dir);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                final int index = i;
                futures.add(executor.submit(() -> d.save("Concurrent memory " + index, new double[] {1, 0})));
            }
            for (Future<?> future : futures) future.get(3, TimeUnit.SECONDS);
        }
        check(new Diary(dir).size() == 30, "concurrent writers overwrote memories");
    }
    public static void pendingSaveFailureDoesNotReportSuccess() throws Exception {
        Path file = temporary().resolve("not-a-directory"); Files.writeString(file, "occupied");
        try { new PendingConversations(file).keep(List.of(Message.user("original"))); throw new AssertionError("failed pending save reported success"); }
        catch (IllegalStateException expected) { }
    }
    public static void main(String[] args) throws Exception {
        int passed = 0;
        for (var method : ReliabilityChecks.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(method.getModifiers()) || method.getName().equals("main")) continue;
            try { method.invoke(null); System.out.println("PASS " + method.getName()); passed++; }
            catch (java.lang.reflect.InvocationTargetException e) { throw new AssertionError(method.getName(), e.getCause()); }
        }
        System.out.println("Reliability checks passed: " + passed);
    }
}
