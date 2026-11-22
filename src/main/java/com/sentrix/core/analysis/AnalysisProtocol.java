package com.sentrix.core.analysis;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Draft transport contract; feature calculation remains in the Python service. */
public final class AnalysisProtocol {
    public static final String CONTRACT_VERSION = "sentrix-analysis-v1-draft";

    private AnalysisProtocol() {}

    public enum Decision { ANOMALY, NORMAL, ABSTAIN }
    public enum CandidateKind { NODE, EDGE }

    @Schema(name = "ModelAnalysisRequest")
    public record Request(String contractVersion, UUID jobId, long environmentId, String namespace,
                          Instant windowStart, Instant windowEnd, String featureSchemaVersion, String modelVersion) {}

    @Schema(name = "AnalysisResult")
    public record Response(String contractVersion, UUID jobId, long environmentId, String namespace,
                           Instant windowStart, Instant windowEnd, String featureSchemaVersion, String modelVersion,
                           Decision decision, Double score, List<Candidate> candidates, String abstainReason) {}

    @Schema(name = "AnalysisCandidate")
    public record Candidate(int rank, CandidateKind kind, String service, String source, String target,
                            String faultType, double score, List<String> evidence) {}
}
