package ru.rainedev.raine.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import javax.net.ssl.SSLSession;
import ru.rainedev.raine.config.Config;

/** Exercises the real request/retry code with a deterministic transport and clock delay. */
public final class HttpClientChecks {
    private HttpClientChecks() {}
    private record Reply(int statusCode, String body, HttpRequest request, HttpHeaders headers) implements HttpResponse<String> {
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
    private static final class Transport implements OpenAiCompatibleClient.Sender {
        int sent;
        final Queue<Integer> codes = new ArrayDeque<>();
        final List<Duration> delays = new ArrayList<>();
        String retryAfter = "0";
        boolean offline;
        @Override public HttpResponse<String> send(HttpRequest request) throws IOException {
            sent++;
            if (offline) throw new IOException("offline");
            int status = codes.isEmpty() ? 200 : codes.remove();
            String body = status == 200 ? "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}" : "endpoint error";
            check(request.method().equals("POST") && request.headers().firstValue("Authorization").orElse("").equals("Bearer fake-key"), "request changed");
            return new Reply(status, body, request, HttpHeaders.of(Map.of("Retry-After", List.of(retryAfter)), (a, b) -> true));
        }
        OpenAiCompatibleClient client() {
            return new OpenAiCompatibleClient(new Config.Llm("http://example.invalid/", "test", "fake-key", 0),
                    "embedding-test", this, delays::add);
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void rateLimitRetriesRequest() {
        Transport transport = new Transport(); transport.codes.addAll(List.of(429, 200)); transport.retryAfter = "120";
        check(transport.client().chat("test", List.of(Message.user("test")), null).text().equals("ok"), "retry did not succeed");
        check(transport.sent == 2 && transport.delays.equals(List.of(Duration.ofSeconds(120))), "Retry-After was not respected");
    }
    public static void paymentStopsSharedClientIncludingEmbeddings() {
        Transport transport = new Transport(); transport.codes.add(402); var client = transport.client();
        for (int i = 0; i < 3; i++) {
            try {
                if (i == 1) client.embedding("test"); else client.chat("test", List.of(), null);
                throw new AssertionError("402 swallowed");
            } catch (LlmFailure error) { check(error.status() == 402, "lost status"); }
        }
        check(transport.sent == 1 && transport.delays.isEmpty(), "payment failure kept issuing requests");
    }
    public static void serverFailureRemainsRetryableAfterExhaustion() {
        Transport transport = new Transport(); transport.codes.addAll(List.of(503, 503, 503));
        try { transport.client().chat("test", List.of(), null); throw new AssertionError("503 swallowed"); }
        catch (LlmFailure error) { check(error.status() == 503 && error.retryable(), "lost retryable status"); }
        check(transport.sent == 3 && transport.delays.equals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(4))), "wrong 503 retry limit");
    }
    public static void networkAndPermanentErrorsHaveDifferentPolicies() {
        Transport network = new Transport(); network.offline = true;
        try { network.client().chat("test", List.of(), null); throw new AssertionError("network error swallowed"); }
        catch (java.io.UncheckedIOException expected) { }
        check(network.sent == 3, "wrong network retry count");
        Transport bad = new Transport(); bad.codes.add(400);
        try { bad.client().chat("test", List.of(), null); throw new AssertionError("400 swallowed"); }
        catch (LlmFailure error) { check(!error.retryable(), "400 retryable"); }
        check(bad.sent == 1 && bad.delays.isEmpty(), "400 retried");
    }
    public static void malformedRetryAfterUsesBackoff() {
        check(OpenAiCompatibleClient.retryDelay("garbage", 2).equals(Duration.ofSeconds(4)), "invalid header broke backoff");
        check(OpenAiCompatibleClient.retryDelay("-1", 1).equals(Duration.ofSeconds(2)), "negative header broke backoff");
    }
    public static void main(String[] args) {
        rateLimitRetriesRequest(); paymentStopsSharedClientIncludingEmbeddings(); serverFailureRemainsRetryableAfterExhaustion();
        networkAndPermanentErrorsHaveDifferentPolicies(); malformedRetryAfterUsesBackoff();
        System.out.println("HTTP client checks passed: 5");
    }
}
