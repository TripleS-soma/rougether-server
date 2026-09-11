package com.triples.rougether.lambdapreprocess;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.amazonaws.services.lambda.runtime.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

class PreprocessHandlerTest {
    final JsonMapper json = JsonMapper.builder().build();
    final PreprocessProcessor processor = mock(PreprocessProcessor.class);
    final Context context = mock(Context.class);
    @BeforeEach void setup() {
        when(context.getRemainingTimeInMillis()).thenReturn(180_000);
        when(context.getLogger()).thenReturn(mock(LambdaLogger.class));
    }
    String invoke(Object body) throws Exception {
        var payload = Map.of("Records", List.of(Map.of("messageId", "sqs-id", "body", json.writeValueAsString(body))));
        var output = new ByteArrayOutputStream();
        new PreprocessHandler(processor).handleRequest(new ByteArrayInputStream(json.writeValueAsBytes(payload)), output, context);
        return output.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
    Object event() {
        return Map.of("Records", List.of(Map.of("eventSource", "aws:s3", "eventName", "ObjectCreated:Put",
                "s3", Map.of("bucket", Map.of("name", "bucket"), "object", Map.of("key", "private%2Ftest", "versionId", "v1")))));
    }
    @Test void S3_키를_디코딩하고_성공시_SQS를_재전달하지_않음() throws Exception {
        assertThat(json.readTree(invoke(event())).path("batchItemFailures").isEmpty()).isTrue();
        verify(processor).process("bucket", "private/test", "v1");
    }
    @Test void 처리_실패는_메시지_ID로_재전달을_요청함() throws Exception {
        doThrow(new IOException("S3 timeout")).when(processor).process(anyString(), anyString(), anyString());
        assertThat(json.readTree(invoke(event())).path("batchItemFailures").get(0).path("itemIdentifier").asString()).isEqualTo("sqs-id");
    }
    @Test void 테스트_알림은_이미지_작업없이_소비함() throws Exception {
        assertThat(json.readTree(invoke(Map.of("Event", "s3:TestEvent"))).path("batchItemFailures").isEmpty()).isTrue();
        verifyNoInteractions(processor);
    }
    @Test void 남은_시간이_부족하면_메모리를_할당하기_전에_재전달함() throws Exception {
        when(context.getRemainingTimeInMillis()).thenReturn(20_000);
        assertThat(json.readTree(invoke(event())).path("batchItemFailures").size()).isEqualTo(1);
        verifyNoInteractions(processor);
    }
}
