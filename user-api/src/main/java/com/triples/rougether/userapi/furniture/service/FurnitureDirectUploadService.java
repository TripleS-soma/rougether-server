package com.triples.rougether.userapi.furniture.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.furniture.config.FurnitureGenerationProperties;
import com.triples.rougether.furniture.dto.FurnitureGenerationResponse;
import com.triples.rougether.furniture.error.FurnitureGenerationErrorCode;
import com.triples.rougether.furniture.service.FurnitureGenerationTransactions;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Status;
import com.triples.rougether.infra.assets.AssetProperties;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "furniture.upload.enabled", havingValue = "true")
public class FurnitureDirectUploadService {
    private final FurnitureGenerationTransactions transactions;
    private final S3Presigner furnitureUploadPresigner;
    private final AssetProperties assets;
    private final FurnitureGenerationProperties generation;
    private final Clock clock;

    public record Response(FurnitureGenerationResponse job, String uploadUrl,
                           Map<String, List<String>> headers, Instant uploadUrlExpiresAt) { }

    public Response start(Long userId, UUID requestId, String sha256, long bytes, String contentType, String hint) {
        if (!generation.enabled()) throw new BusinessException(FurnitureGenerationErrorCode.FURNITURE_GENERATION_UNAVAILABLE);
        FurnitureGenerationTransactions.UploadReservation ticket;
        try {
            ticket = transactions.reserveDirect(userId, requestId.toString(), sha256, bytes, contentType, hint == null ? "" : hint.strip());
        } catch (org.springframework.dao.TransientDataAccessException | org.springframework.transaction.TransactionTimedOutException e) {
            throw new BusinessException(FurnitureGenerationErrorCode.FURNITURE_ADMISSION_BUSY);
        }
        if (ticket.job().status() != Status.UPLOADING) return new Response(ticket.job(), null, Map.of(), null);
        Duration duration = Duration.between(clock.instant(), ticket.expiresAt());
        if (duration.compareTo(Duration.ofMinutes(5)) > 0) duration = Duration.ofMinutes(5);
        if (duration.isNegative() || duration.isZero()) return new Response(ticket.job(), null, Map.of(), null);
        var put = PutObjectRequest.builder().bucket(assets.s3().bucket()).key(ticket.rawKey())
                .contentLength(bytes).contentType(contentType).ifNoneMatch("*")
                .checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256))).build();
        Duration signatureDuration = duration;
        var signed = furnitureUploadPresigner.presignPutObject(b -> b.signatureDuration(signatureDuration).putObjectRequest(put));
        var headers = new LinkedHashMap<String, List<String>>();
        signed.signedHeaders().forEach((key, value) -> { if (!key.equalsIgnoreCase("host")) headers.put(key, List.copyOf(value)); });
        return new Response(ticket.job(), signed.url().toString(), Map.copyOf(headers), signed.expiration());
    }
}
