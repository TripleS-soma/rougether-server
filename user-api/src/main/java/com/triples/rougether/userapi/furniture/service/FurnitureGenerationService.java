package com.triples.rougether.userapi.furniture.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.furniture.ai.FurnitureAiClient;
import com.triples.rougether.userapi.furniture.dto.*;
import com.triples.rougether.userapi.furniture.error.FurnitureGenerationErrorCode;
import com.triples.rougether.userapi.global.storage.AssetStorageService;
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
    private final FurnitureAiClient ai;

    public FurnitureGenerationResponse submit(Long userId, UUID requestId, String hint, MultipartFile photo) {
        requireAvailable();
        String target = hint == null ? "" : hint.strip();
        var input = images.photo(photo, target);
        var reservation = transactions.reserve(userId, requestId.toString(), input.digest(), target);
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
        return transactions.feedback(userId, id, request.requestId().toString(), request.feedback().strip());
    }
    private void requireAvailable() {
        if (!ai.available()) throw new BusinessException(FurnitureGenerationErrorCode.FURNITURE_GENERATION_UNAVAILABLE);
    }
    private void deleteQuietly(String key) {
        try { if (!transactions.referencesAsset(key)) storage.delete(key); }
        catch (RuntimeException e) { log.warn("가구 임시 원본 정리 보류"); }
    }
}
