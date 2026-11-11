package com.triples.rougether.userapi.furniture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Action;
import com.triples.rougether.furniture.lambda.FurnitureLambdaProtocol.*;
import com.triples.rougether.furniture.lambda.FurnitureLambdaRunner;
import com.triples.rougether.furniture.service.FurnitureAiFailure;
import com.triples.rougether.furniture.service.FurnitureGenerationTransactions.Claim;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class FurnitureLambdaRunnerAlertTest {
    @Test void 처리한_공급자_실패도_운영_감지_신호를_남긴다(CapturedOutput output) {
        runFailure(new FurnitureAiFailure("PROVIDER_REQUEST_FAILED"));
        assertThat(output.getAll()).contains("FURNITURE_JOB_FAILED action=GENERATE code=PROVIDER_REQUEST_FAILED");
    }

    @Test void 원문_예외와_비정상_실패문구는_로그에_노출하지_않는다(CapturedOutput output) {
        runFailure(new FurnitureAiFailure("private photograph and provider body"));
        runFailure(new IllegalStateException("private photograph and provider body"));
        assertThat(output.getAll()).contains("code=UNKNOWN_FAILURE", "code=STORAGE_OR_PROCESSING_FAILED")
                .doesNotContain("private photograph and provider body");
    }

    private void runFailure(RuntimeException failure) {
        var claim = mock(Claim.class);
        when(claim.action()).thenReturn(Action.GENERATE);
        var calls = new AtomicInteger();
        var runner = new FurnitureLambdaRunner(command -> calls.getAndIncrement() == 0
                ? new Reply(Status.READY, claim) : Reply.of(Status.TERMINAL),
                (work, execution) -> { throw failure; }, Duration.ofSeconds(180));
        assertThat(runner.run(new Message("job", "execution"), "owner", () -> 600_000)).isTrue();
    }
}
