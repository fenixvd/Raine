package ru.rainedev.raine.llm;

/** Status-bearing endpoint error; response bodies never need to be parsed by callers. */
public final class LlmFailure extends IllegalStateException {
    private final int status;
    public LlmFailure(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
    public boolean retryable() { return status == 408 || status == 429 || status >= 500; }
    public boolean requiresOperator() { return status == 401 || status == 402 || status == 403; }
}
