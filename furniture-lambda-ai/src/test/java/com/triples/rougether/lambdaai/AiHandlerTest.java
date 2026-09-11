package com.triples.rougether.lambdaai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.amazonaws.services.lambda.runtime.*;
import com.triples.rougether.furniture.lambda.*;
import com.triples.rougether.furniture.lambda.FurnitureLambdaProtocol.*;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AiHandlerTest {
    final JsonMapper json = JsonMapper.builder().build();
    Context context() {
        var context = mock(Context.class);
        when(context.getAwsRequestId()).thenReturn("request-1");
        when(context.getRemainingTimeInMillis()).thenReturn(600_000);
        when(context.getLogger()).thenReturn(mock(LambdaLogger.class));
        return context;
    }
    byte[] event(int count) {
        var record = Map.of("messageId", "message-1", "body", json.writeValueAsString(new Message(UUID.randomUUID().toString(), UUID.randomUUID().toString())));
        return json.writeValueAsBytes(Map.of("Records", Collections.nCopies(count, record)));
    }
    @Test void 포화된_실행은_SQS_부분실패로_반환() throws Exception {
        var runner = new FurnitureLambdaRunner(c -> Reply.of(Status.BUSY), (c, id) -> { throw new AssertionError(); }, Duration.ofSeconds(30));
        var output = new ByteArrayOutputStream();
        new AiHandler(runner).handleRequest(new ByteArrayInputStream(event(1)), output, context());
        assertThat(json.readTree(output.toByteArray()).path("batchItemFailures").get(0).path("itemIdentifier").asText()).isEqualTo("message-1");
    }
    @Test void 중복_전달은_AI_호출없이_ACK() throws Exception {
        var runner = new FurnitureLambdaRunner(c -> Reply.of(Status.DUPLICATE), (c, id) -> { throw new AssertionError(); }, Duration.ofSeconds(30));
        var output = new ByteArrayOutputStream();
        new AiHandler(runner).handleRequest(new ByteArrayInputStream(event(1)), output, context());
        assertThat(json.readTree(output.toByteArray()).path("batchItemFailures").size()).isZero();
    }
    @Test void 배치가_잘못되면_어떤_작업도_시작하지_않음() {
        var calls = new AtomicInteger();
        var runner = new FurnitureLambdaRunner(c -> { calls.incrementAndGet(); return Reply.of(Status.TERMINAL); }, (c, id) -> null, Duration.ofSeconds(30));
        assertThatThrownBy(() -> new AiHandler(runner).handleRequest(new ByteArrayInputStream(event(2)), new ByteArrayOutputStream(), context())).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);
    }
    @Test void 미확정_제어호출은_같은_명령만_세번_전송() {
        var commands = new ArrayList<Command>();
        var runner = new FurnitureLambdaRunner(c -> { commands.add(c); throw new IllegalStateException(); }, (c, id) -> { throw new AssertionError(); }, Duration.ofSeconds(30));
        assertThatThrownBy(() -> runner.run(new Message(UUID.randomUUID().toString(), UUID.randomUUID().toString()), "request", () -> 600_000)).isInstanceOf(IllegalStateException.class);
        assertThat(commands).hasSize(3).allMatch(c -> c.equals(commands.getFirst()));
    }
}
