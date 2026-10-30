package com.triples.rougether.userapi.furniture.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Status;
import com.triples.rougether.furniture.dto.FurnitureGenerationResponse;
import com.triples.rougether.furniture.error.FurnitureGenerationErrorCode;
import com.triples.rougether.furniture.service.FurnitureGenerationTransactions;
import com.triples.rougether.infra.assets.AssetProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 구버전 앱의 multipart 계약은 유지하고 원본만 S3로 중계함. */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "furniture.upload.multipart-lambda-enabled", havingValue = "true")
public class FurnitureMultipartUploadService {
    private final FurnitureGenerationTransactions transactions;
    private final FurnitureGenerationQueryService queries;
    private final S3Client s3;
    private final AssetProperties assets;

    public FurnitureGenerationResponse submit(Long userId, UUID requestId, String hint, MultipartFile file) {
        var photo = FurnitureRawPhoto.inspect(file, hint);
        FurnitureGenerationTransactions.UploadReservation ticket;
        try {
            ticket = transactions.reserveMultipart(userId, requestId.toString(), photo.requestDigest(),
                    photo.sha256(), photo.bytes(), photo.contentType(), hint);
        } catch (org.springframework.dao.TransientDataAccessException | org.springframework.transaction.TransactionTimedOutException e) {
            throw new BusinessException(FurnitureGenerationErrorCode.FURNITURE_ADMISSION_BUSY);
        }
        // 이전 경로에서 이미 접수한 작업은 원본 업로드 방식과 실행권을 변경하지 않음.
        if (ticket.job().status() != Status.UPLOADING || ticket.rawKey() == null) return ticket.job();
        var put = PutObjectRequest.builder().bucket(assets.s3().bucket()).key(ticket.rawKey())
                .contentLength(photo.bytes()).contentType(photo.contentType()).ifNoneMatch("*")
                .checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(photo.sha256())))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(30)).apiCallAttemptTimeout(Duration.ofSeconds(15)))
                .build();
        try {
            s3.putObject(put, RequestBody.fromContentProvider(() -> {
                try { return file.getInputStream(); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            }, photo.bytes(), photo.contentType()));
        } catch (S3Exception e) {
            if (e.statusCode() != 412) throw retryable(ticket.job().id());
        } catch (SdkException | UncheckedIOException e) { throw retryable(ticket.job().id()); }
        return queries.get(userId, ticket.job().id());
    }

    private BusinessException retryable(String id) {
        // PUT 응답 유실일 수 있으므로 즉시 실패/환불하거나 원본을 삭제하지 않음.
        log.warn("가구 원본 중계 확인 보류 job={}", id);
        return new BusinessException(FurnitureGenerationErrorCode.FURNITURE_UPLOAD_BUSY);
    }
}
