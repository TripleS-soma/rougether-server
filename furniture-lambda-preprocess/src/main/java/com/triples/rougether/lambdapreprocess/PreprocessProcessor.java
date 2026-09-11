package com.triples.rougether.lambdapreprocess;

import com.triples.rougether.common.furniture.FurniturePreprocessProtocol;
import com.triples.rougether.common.furniture.FurniturePreprocessProtocol.*;
import com.triples.rougether.preprocessing.PreparedPhoto;
import com.triples.rougether.preprocessing.VipsPhotoPreprocessor;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Function;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

// 네트워크 대기는 동기로 유지함. DB 잠금은 control 호출 안에서 끝나며 S3/이미지 처리와 겹치지 않음.
public final class PreprocessProcessor {
    private final S3Client s3;
    private final Function<Command, Reply> control;
    private final Function<byte[], PreparedPhoto> prepare;
    private final String bucket;

    public PreprocessProcessor(S3Client s3, Function<Command, Reply> control,
                               Function<byte[], PreparedPhoto> prepare, String bucket) {
        this.s3 = s3; this.control = control; this.prepare = prepare; this.bucket = Objects.requireNonNull(bucket);
    }

    public void process(String sourceBucket, String sourceKey, String sourceVersion) throws IOException {
        String prefix = "private/furniture-generation/raw/";
        if (!bucket.equals(sourceBucket) || !sourceKey.startsWith(prefix) || sourceVersion == null
                || sourceVersion.isBlank() || "null".equals(sourceVersion) || sourceVersion.length() > 1024) {
            throw new IllegalArgumentException("허용되지 않은 S3 원본");
        }
        String job = UUID.fromString(sourceKey.substring(prefix.length())).toString();
        if (!FurniturePreprocessProtocol.sourceKey(job).equals(sourceKey)) throw new IllegalArgumentException("원본 경로 오류");
        Command start = Command.start(job, sourceKey, sourceVersion);
        Reply gate = control.apply(start);
        if (gate.status() != Status.READY) return;
        Input expected = Objects.requireNonNull(gate.input());
        if (expected.bytes() < 1 || expected.bytes() > VipsPhotoPreprocessor.MAX_BYTES
                || expected.sha256() == null || !expected.sha256().matches("[a-f0-9]{64}")) throw new IllegalStateException("원본 계약 오류");
        String outputKey = FurniturePreprocessProtocol.outputKey(job);
        var previous = completed(outputKey, start, expected);
        if (previous != null) {
            control.apply(start.complete(outputKey, previous.versionId(), previous.metadata().get("png-sha256")));
            return;
        }
        byte[] source = null;
        try (var stream = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(sourceKey).versionId(sourceVersion).build())) {
            if (!Objects.equals(stream.response().contentLength(), expected.bytes())
                    || !expected.contentType().equals(stream.response().contentType())) {
                // 잘못된 대용량 응답을 close가 연결 재사용 목적으로 끝까지 읽지 않도록 중단함.
                stream.abort();
            } else {
                try {
                    source = stream.readNBytes(VipsPhotoPreprocessor.MAX_BYTES + 1);
                    if (source.length > VipsPhotoPreprocessor.MAX_BYTES) stream.abort();
                } catch (IOException | RuntimeException e) { stream.abort(); throw e; }
            }
        }
        if (source == null || source.length != expected.bytes() || !sha256(source).equals(expected.sha256())) {
            control.apply(start.fail("SOURCE_UPLOAD_MISMATCH")); return;
        }
        PreparedPhoto photo;
        try { photo = prepare.apply(source); }
        catch (IllegalArgumentException e) { control.apply(start.fail("FURNITURE_PHOTO_INVALID")); return; }
        // 별도 PNG Arena만 전송 중 유지함. 처리 Arena는 prepare가 반환하기 전에 이미 닫힘.
        try (photo) {
            var put = PutObjectRequest.builder().bucket(bucket).key(outputKey).contentType("image/png")
                    .contentLength(photo.size()).ifNoneMatch("*")
                    .checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(photo.sha256())))
                    .metadata(Map.of("source-version", sourceVersion, "job-id", job, "source-sha256", expected.sha256(),
                            "png-sha256", photo.sha256())).build();
            try {
                s3.putObject(put, RequestBody.fromContentProvider(photo::openStream, photo.size(), "image/png"));
            } catch (S3Exception e) { if (e.statusCode() != 412) throw e; }
        }
        // PUT 성공→응답 유실도 재전달에서 HEAD로 복구함. 저장 확인 이전에는 AI를 큐에 넣지 않음.
        var saved = completed(outputKey, start, expected);
        if (saved == null) throw new IllegalStateException("전처리 결과 저장을 확인하지 못함");
        control.apply(start.complete(outputKey, saved.versionId(), saved.metadata().get("png-sha256")));
    }

    private HeadObjectResponse completed(String key, Command start, Input expected) {
        HeadObjectResponse head;
        try { head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).checksumMode(ChecksumMode.ENABLED).build()); }
        catch (S3Exception e) { if (e.statusCode() == 404) return null; throw e; }
        String sha = head.metadata().get("png-sha256");
        if (!start.jobId().equals(head.metadata().get("job-id")) || !start.sourceVersion().equals(head.metadata().get("source-version"))
                || !expected.sha256().equals(head.metadata().get("source-sha256")) || !"image/png".equals(head.contentType())
                || head.contentLength() == null || head.contentLength() < 1 || head.contentLength() > VipsPhotoPreprocessor.MAX_BYTES
                || head.versionId() == null || "null".equals(head.versionId()) || head.versionId().isBlank()
                || sha == null || !sha.matches("[a-f0-9]{64}")
                || !Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha)).equals(head.checksumSHA256())) {
            throw new IllegalStateException("전처리 출력 소유권 또는 체크섬 불일치");
        }
        return head;
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
