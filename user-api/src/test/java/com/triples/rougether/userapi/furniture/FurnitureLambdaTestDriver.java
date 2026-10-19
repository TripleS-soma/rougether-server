package com.triples.rougether.userapi.furniture;

import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob;
import com.triples.rougether.domain.furniture.repository.FurnitureGenerationJobRepository;
import com.triples.rougether.furniture.lambda.FurnitureLambdaControl;
import com.triples.rougether.furniture.lambda.FurnitureLambdaProtocol.*;
import com.triples.rougether.furniture.lambda.FurnitureLambdaStage;
import com.triples.rougether.furniture.service.FurnitureAiFailure;
import com.triples.rougether.furniture.service.FurnitureGenerationTransactions.Claim;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 실제 Lambda 제어와 AI 단계를 한 단계씩 진행해 중간 영속 상태를 검증함. */
public final class FurnitureLambdaTestDriver {
    private final FurnitureLambdaControl control;
    private final FurnitureLambdaStage stage;
    private final FurnitureGenerationJobRepository jobs;
    private final Map<String, Execution> executions = new HashMap<>();

    public FurnitureLambdaTestDriver(FurnitureLambdaControl control, FurnitureLambdaStage stage,
            FurnitureGenerationJobRepository jobs) {
        this.control = control;
        this.stage = stage;
        this.jobs = jobs;
    }

    public void runNext() {
        jobs.findAll().stream()
                .filter(job -> job.getStatus() == FurnitureGenerationJob.Status.QUEUED
                        || job.getStatus() == FurnitureGenerationJob.Status.PROCESSING)
                .findFirst().ifPresent(job -> runNext(job.getId()));
    }

    public Reply runNext(String jobId) {
        var execution = execution(jobId);
        if (execution.pending != null) return send(execution);
        var reply = start(jobId);
        if (reply.status() != Status.READY) return reply;
        Result result;
        try {
            result = stage.execute(reply.claim(), execution.id);
        } catch (RuntimeException failure) {
            result = Result.failure(reply.claim().action(), failure instanceof FurnitureAiFailure aiFailure
                    ? aiFailure.code() : "STORAGE_OR_PROCESSING_FAILED");
        }
        return checkpoint(jobId, result);
    }

    public Reply start(String jobId) {
        var execution = execution(jobId);
        if (execution.reply == null || execution.reply.status() == Status.BUSY) {
            execution.pending = new Command(jobId, execution.id, execution.owner, 0, null);
            return send(execution);
        }
        return execution.reply;
    }

    public Claim currentClaim(String jobId) {
        return start(jobId).claim();
    }

    public Reply checkpoint(String jobId, Result result) {
        var execution = execution(jobId);
        execution.pending = new Command(jobId, execution.id, execution.owner, execution.sequence + 1, result);
        return send(execution);
    }

    private Execution execution(String jobId) {
        String id = jobs.findById(jobId).orElseThrow().getExecutionId();
        return executions.computeIfAbsent(id, Execution::new);
    }

    private Reply send(Execution execution) {
        RuntimeException last = null;
        // 실제 Runner와 동일하게 DB 명령만 재전송하며 AI 결과와 명령은 유지함.
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                var reply = control.handle(execution.pending);
                execution.sequence = execution.pending.sequence();
                execution.pending = null;
                execution.reply = reply;
                return reply;
            } catch (RuntimeException failure) {
                last = failure;
            }
        }
        throw last;
    }

    private static final class Execution {
        private final String id;
        private final String owner = UUID.randomUUID().toString();
        private int sequence;
        private Reply reply;
        private Command pending;

        private Execution(String id) { this.id = id; }
    }
}
