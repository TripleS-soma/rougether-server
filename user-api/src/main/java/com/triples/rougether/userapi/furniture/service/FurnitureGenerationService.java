package com.triples.rougether.userapi.furniture.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.furniture.config.FurnitureGenerationProperties;
import com.triples.rougether.furniture.service.FurnitureGenerationTransactions;
import com.triples.rougether.furniture.service.FurnitureImages;
import com.triples.rougether.userapi.furniture.dto.FurnitureFeedbackRequest;
import com.triples.rougether.furniture.dto.FurnitureGenerationResponse;
import com.triples.rougether.furniture.error.FurnitureGenerationErrorCode;
import com.triples.rougether.infra.assets.AssetStorageService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class FurnitureGenerationService {
    private final FurnitureGenerationTransactions transactions;
    private final FurnitureImages images;
    private final AssetStorageService storage;
    private final FurnitureGenerationProperties config;

    public FurnitureGenerationResponse submit(Long userId, UUID requestId, String hint, MultipartFile photo) {
        requireAvailable();
        String target = hint == null ? "" : hint.strip();
        var input = images.photo(photo, target);
        FurnitureGenerationTransactions.Reservation reservation;
        try { reservation = transactions.reserve(userId, requestId.toString(), input.digest(), target); }
        catch (org.springframework.dao.TransientDataAccessException | org.springframework.transaction.TransactionTimedOutException e) {
            throw new BusinessException(FurnitureGenerationErrorCode.FURNITURE_ADMISSION_BUSY);
        }
        if (!reservation.created()) return reservation.job();
        String id = reservation.job().id();
        String key = null;
        try {
            key = storage.upload(input.png(), "image/png", "private/furniture-generation/" + id + "/source");
            if (!transactions.uploaded(userId, id, key)) deleteQuietly(key);
        } catch (RuntimeException e) {
            if (key != null) deleteQuietly(key);
            transactions.uploadFailed(userId, id);
            log.warn("가구 원본 업로드 실패 job={}", id);
        }
        return transactions.get(userId, id);
    }

    public FurnitureGenerationResponse get(Long userId, String id) { return transactions.get(userId, id); }
    public List<FurnitureGenerationResponse> list(Long userId) { return transactions.list(userId); }
    public FurnitureGenerationResponse feedback(Long userId, String id, FurnitureFeedbackRequest request) {
        requireAvailable();
        try { return transactions.feedback(userId, id, request.requestId().toString(), request.feedback().strip()); }
        catch (org.springframework.dao.TransientDataAccessException | org.springframework.transaction.TransactionTimedOutException e) {
            throw new BusinessException(FurnitureGenerationErrorCode.FURNITURE_ADMISSION_BUSY);
        }
    }
    private void requireAvailable() {
        if (!config.enabled()) throw new BusinessException(FurnitureGenerationErrorCode.FURNITURE_GENERATION_UNAVAILABLE);
    }
    private void deleteQuietly(String key) {
        try { if (!transactions.referencesAsset(key)) storage.delete(key); }
        catch (RuntimeException e) { log.warn("가구 임시 원본 정리 보류"); }
    }
}
