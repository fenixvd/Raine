package ru.rainedev.raine.reliability;

import org.junit.jupiter.api.Test;
import ru.rainedev.raine.llm.HttpClientChecks;

class HttpReliabilityTest {
    @Test void retriesRateLimit() throws Exception { HttpClientChecks.rateLimitRetriesRequest(); }
    @Test void stopsRequestsAfterPaymentFailure() throws Exception { HttpClientChecks.paymentStopsSharedClientIncludingEmbeddings(); }
    @Test void limitsServerErrorRetries() throws Exception { HttpClientChecks.serverFailureRemainsRetryableAfterExhaustion(); }
    @Test void distinguishesNetworkFromPermanentErrors() { HttpClientChecks.networkAndPermanentErrorsHaveDifferentPolicies(); }
    @Test void handlesMalformedRetryHeader() { HttpClientChecks.malformedRetryAfterUsesBackoff(); }
}
