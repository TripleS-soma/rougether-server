package com.triples.rougether.furniture.lambda;

import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Action;
import com.triples.rougether.furniture.ai.FurnitureAiClient.*;
import com.triples.rougether.furniture.service.FurnitureGenerationTransactions.Claim;

public final class FurnitureLambdaProtocol {
    private FurnitureLambdaProtocol() { }
    public record Message(String jobId, String executionId) { }
    public record Result(Action action, Extracted extracted, Review review, String assetKey,
                         boolean hardPass, long inputTokens, long outputTokens, String failureCode) {
        public static Result failure(Action action, String code) {
            return new Result(action, null, null, null, false, 0, 0, code);
        }
    }
    public record Command(String jobId, String executionId, String owner, int sequence, Result result) { }
    public enum Status { READY, TERMINAL, DUPLICATE, BUSY, STALE }
    public record Reply(Status status, Claim claim) {
        public static Reply of(Status status) { return new Reply(status, null); }
    }
}
