package com.triples.rougether.furniture.lambda;

import static com.triples.rougether.furniture.lambda.FurnitureLambdaProtocol.*;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Action;
import com.triples.rougether.furniture.service.FurnitureAiFailure;
import java.time.Duration;
import java.util.function.*;

/** 통신 재시도는 고정된 DB 명령만 반복한다. 유료 AI 실행을 감싸는 재시도는 없다. */
public class FurnitureLambdaRunner {
    private final Function<Command, Reply> control;
    private final BiFunction<com.triples.rougether.furniture.service.FurnitureGenerationTransactions.Claim, String, Result> stage;
    private final Duration requestTimeout;
    public FurnitureLambdaRunner(Function<Command, Reply> control,
            BiFunction<com.triples.rougether.furniture.service.FurnitureGenerationTransactions.Claim, String, Result> stage,
            Duration requestTimeout) {
        this.control = control; this.stage = stage; this.requestTimeout = requestTimeout;
    }
    public boolean run(Message message, String owner, IntSupplier remainingMillis) {
        int sequence = 0;
        Reply reply = send(new Command(message.jobId(), message.executionId(), owner, sequence, null));
        while (reply.status() == Status.READY) {
            var claim = reply.claim();
            Action action = claim.action();
            long calls = action == Action.EXTRACT || action == Action.REVIEW ? 1 : 2;
            Result result;
            if (remainingMillis.getAsInt() < requestTimeout.toMillis() * calls + 30_000) {
                result = Result.failure(action, "EXECUTION_TIME_BUDGET_EXHAUSTED");
            } else {
                try { result = stage.apply(claim, message.executionId()); }
                catch (RuntimeException e) {
                    result = Result.failure(action, e instanceof FurnitureAiFailure f ? f.code() : "STORAGE_OR_PROCESSING_FAILED");
                }
            }
            reply = send(new Command(message.jobId(), message.executionId(), owner, ++sequence, result));
        }
        return reply.status() != Status.BUSY;
    }
    private Reply send(Command command) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try { return control.apply(command); }
            catch (RuntimeException e) { last = e; }
        }
        // SQS 재전달은 새 owner이므로 이미 시작한 실행을 다시 수행하지 못함.
        throw last;
    }
}
