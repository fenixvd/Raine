package ru.rainedev.raine.reliability;

import org.junit.jupiter.api.Test;

class ReliabilityTest {
    @Test void preservesOriginalOnEmbeddingFailure() throws Exception { ReliabilityChecks.failedEmbeddingPreservesOriginals(); }
    @Test void stagesAllEmbeddingsBeforeWriting() throws Exception { ReliabilityChecks.partialEmbeddingFailureWritesNothing(); }
    @Test void rejectsMalformedReplacements() throws Exception { ReliabilityChecks.malformedReplacementDoesNotForget(); }
    @Test void mergesAndForgetsExplicitly() throws Exception { ReliabilityChecks.explicitForgetAndSuccessfulMerge(); }
    @Test void enforcesBudgets() throws Exception { ReliabilityChecks.requestAndTokenLimitsPreserveOriginals(); }
    @Test void stopsOnWakeUp() throws Exception { ReliabilityChecks.wakeDuringRequestPreservesOriginals(); }
    @Test void keepsPartialToolResults() throws Exception { ReliabilityChecks.partialToolsKeepProtocolAndSkipRemaining(); }
    @Test void resumesWithoutRepeatingActions() throws Exception { ReliabilityChecks.networkResumeDoesNotReopenOrResend(); }
    @Test void sleepDeadlineIncludesConsolidation() throws Exception { ReliabilityChecks.restUsesOriginalDeadline(); }
    @Test void shutdownStopsConsolidation() throws Exception { ReliabilityChecks.stopInterruptsConsolidationPredicate(); }
    @Test void persistsPendingWithoutCollisions() throws Exception { ReliabilityChecks.pendingAndAtomicFilesRemainReadable(); }
    @Test void reportsFailedDiarySave() throws Exception { ReliabilityChecks.strictDiarySaveRetainsFailureSignal(); }
    @Test void reportsRestartRequired() throws Exception { ReliabilityChecks.reloadShowsRestartOnlyChanges(); }
    @Test void classifiesEndpointErrors() { ReliabilityChecks.httpErrorsAreClassified(); }
    @Test void preservesActiveTurnAcrossPriorityNotification() throws Exception { ReliabilityChecks.retryIsNotPreemptedByPriorityNotification(); }
    @Test void retainsExhaustedNotification() throws Exception { ReliabilityChecks.exhaustedRetriesAreReportedOnce(); }
    @Test void suspendsAfterPaymentFailure() throws Exception { ReliabilityChecks.paymentSuspendsLaterNotifications(); }
    @Test void serialisesDiaryWrites() throws Exception { ReliabilityChecks.concurrentDiaryWritesHaveUniqueIds(); }
    @Test void reportsPendingWriteFailure() throws Exception { ReliabilityChecks.pendingSaveFailureDoesNotReportSuccess(); }
}
