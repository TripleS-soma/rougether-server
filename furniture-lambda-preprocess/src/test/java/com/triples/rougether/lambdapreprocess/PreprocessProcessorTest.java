package com.triples.rougether.lambdapreprocess;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.triples.rougether.common.furniture.FurniturePreprocessProtocol;
import com.triples.rougether.common.furniture.FurniturePreprocessProtocol.*;
import com.triples.rougether.preprocessing.PreparedPhoto;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

class PreprocessProcessorTest {
    S3Client s3;
    Function<Command, Reply> control;
    Function<byte[], PreparedPhoto> prepare;
    PreparedPhoto photo;
    PreprocessProcessor processor;
    List<Command> commands;
    final String job = UUID.randomUUID().toString(), bucket = "test-bucket";
    final String key = FurniturePreprocessProtocol.sourceKey(job), output = FurniturePreprocessProtocol.outputKey(job);
    final byte[] raw = {1, 2, 3}, png = {4, 5, 6, 7};
    String rawSha, pngSha;
    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        s3 = mock(S3Client.class); prepare = mock(Function.class); photo = mock(PreparedPhoto.class);
        rawSha = sha(raw); pngSha = sha(png); commands = new ArrayList<>();
        control = command -> {
            commands.add(command);
            return command.phase() == Phase.START ? new Reply(Status.READY, new Input(rawSha, raw.length, "image/jpeg"))
                    : Reply.of(command.phase() == Phase.FAIL ? Status.TERMINAL : Status.DONE);
        };
        processor = new PreprocessProcessor(s3, control, prepare, bucket);
        when(s3.getObject(any(GetObjectRequest.class))).thenAnswer(i -> new ResponseInputStream<>(GetObjectResponse.builder()
                .contentLength((long) raw.length).contentType("image/jpeg").build(), AbortableInputStream.create(new ByteArrayInputStream(raw))));
        when(prepare.apply(any())).thenReturn(photo);
        when(photo.size()).thenReturn((long) png.length); when(photo.sha256()).thenReturn(pngSha);
        when(photo.openStream()).thenAnswer(i -> new ByteArrayInputStream(png));
    }
    String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    HeadObjectResponse head() {
        return HeadObjectResponse.builder().versionId("png-v1").contentLength((long) png.length).contentType("image/png")
                .checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(pngSha)))
                .metadata(Map.of("job-id", job, "source-version", "raw-v1", "source-sha256", rawSha, "png-sha256", pngSha)).build();
    }
    void absentThenSaved() { when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build()).thenReturn(head()); }
    void process() throws IOException { processor.process(bucket, key, "raw-v1"); }

    @Test void 동기_전송의_재읽기가_끝난뒤_PNG를_닫고_검증한_버전으로_완료함() throws Exception {
        absentThenSaved();
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(i -> {
            verify(photo, never()).close();
            var put = (PutObjectRequest) i.getArgument(0);
            assertThat(put.ifNoneMatch()).isEqualTo("*");
            assertThat(put.key()).isEqualTo(output);
            RequestBody body = i.getArgument(1);
            try (var first = body.contentStreamProvider().newStream(); var retry = body.contentStreamProvider().newStream()) {
                assertThat(first.read()).isEqualTo(4);
                assertThat(retry.readAllBytes()).isEqualTo(png);
            }
            return PutObjectResponse.builder().versionId("png-v1").build();
        });
        process();
        verify(photo).close();
        assertThat(commands).extracting(Command::phase).containsExactly(Phase.START, Phase.COMPLETE);
        assertThat(commands.getLast().outputVersion()).isEqualTo("png-v1");
        verify(s3).getObject(argThat((GetObjectRequest r) -> "raw-v1".equals(r.versionId())));
    }
    @Test void PUT_성공후_응답유실은_재전달에서_기존_출력을_사용해_복구함() throws Exception {
        absentThenSaved();
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(new IllegalStateException("응답 유실"));
        assertThatThrownBy(this::process).isInstanceOf(IllegalStateException.class);
        verify(photo).close();
        process();
        verify(prepare, times(1)).apply(any());
        verify(s3, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThat(commands.getLast().phase()).isEqualTo(Phase.COMPLETE);
    }
    @Test void DB_완료응답이_유실되어도_다음_전달에서_이미지처리를_반복하지_않음() throws Exception {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(head());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var p = new PreprocessProcessor(s3, c -> {
            var result = control.apply(c);
            if (c.phase() == Phase.COMPLETE && calls.getAndIncrement() == 0) throw new IllegalStateException("DB 응답 유실");
            return result;
        }, prepare, bucket);
        assertThatThrownBy(() -> p.process(bucket, key, "raw-v1")).isInstanceOf(IllegalStateException.class);
        p.process(bucket, key, "raw-v1");
        verifyNoInteractions(prepare);
        assertThat(calls).hasValue(2);
    }
    @Test void 원본_해시_불일치는_전처리와_AI_등록_전에_실패함() throws Exception {
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());
        rawSha = "a".repeat(64);
        process();
        verifyNoInteractions(prepare);
        assertThat(commands.getLast().failureCode()).isEqualTo("SOURCE_UPLOAD_MISMATCH");
    }
    @Test void 잘못된_이미지는_영구실패_명령으로_종료함() throws Exception {
        absentThenSaved(); when(prepare.apply(any())).thenThrow(new IllegalArgumentException("bad image"));
        process();
        assertThat(commands.getLast().failureCode()).isEqualTo("FURNITURE_PHOTO_INVALID");
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
    @Test void S3_일시실패는_환불명령없이_재시도하며_PNG는_닫힘() throws Exception {
        absentThenSaved();
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(S3Exception.builder().statusCode(503).build());
        assertThatThrownBy(this::process).isInstanceOf(S3Exception.class);
        verify(photo).close();
        assertThat(commands).extracting(Command::phase).containsExactly(Phase.START);
    }
    @Test void 출력_버전이_다른_원본을_가리키면_완료하지_않음() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(head().toBuilder().metadata(Map.of("source-version", "other")).build());
        assertThatThrownBy(this::process).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(prepare);
        assertThat(commands).hasSize(1);
    }
    @Test void 이미_종료된_작업과_잘못된_버킷은_S3를_읽지_않음() throws Exception {
        var p = new PreprocessProcessor(s3, c -> Reply.of(Status.TERMINAL), prepare, bucket);
        p.process(bucket, key, "raw-v1");
        assertThatThrownBy(() -> p.process("other", key, "raw-v1")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(s3, prepare);
    }
    @Test void 선언과_다른_대용량_응답은_읽지않고_연결을_중단한뒤_실패_처리함() throws Exception {
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());
        var aborted = new java.util.concurrent.atomic.AtomicBoolean();
        var input = mock(InputStream.class);
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(GetObjectResponse.builder()
                .contentLength(100_000_000L).contentType("image/jpeg").build(),
                AbortableInputStream.create(input, () -> aborted.set(true))));
        var p = new PreprocessProcessor(s3, c -> {
            if (c.phase() == Phase.FAIL) assertThat(aborted).isTrue();
            return control.apply(c);
        }, prepare, bucket);
        p.process(bucket, key, "raw-v1");
        verify(input, never()).read(any(byte[].class), anyInt(), anyInt());
        verifyNoInteractions(prepare);
        assertThat(commands.getLast().failureCode()).isEqualTo("SOURCE_UPLOAD_MISMATCH");
    }
}
