package com.triples.rougether.furniture.lambda;

import static com.triples.rougether.furniture.lambda.FurnitureLambdaProtocol.*;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Action;
import com.triples.rougether.furniture.service.FurnitureAiFailure;
import java.time.Duration;
import java.util.function.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 통신 재시도는 고정된 DB 명령만 반복한다. 유료 AI 실행을 감싸는 재시도는 없다. */
public class FurnitureLambdaRunner {
    private static final Logger log = LoggerFactory.getLogger(FurnitureLambdaRunner.class);
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
            if (result.failureCode() != null) {
                String code = result.failureCode().matches("[A-Z_]{1,50}") ? result.failureCode() : "UNKNOWN_FAILURE";
                // 예외를 작업 실패로 처리해 Lambda Errors=0이어도 운영 감시가 잡을 수 있게 한다.
                // 사진, 프롬프트, 계정, 공급자 오류 본문은 기록하지 않는다.
                log.error("FURNITURE_JOB_FAILED action={} code={}", action, code);
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
