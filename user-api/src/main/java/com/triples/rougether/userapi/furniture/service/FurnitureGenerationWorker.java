package com.triples.rougether.userapi.furniture.service;

import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Action;
import com.triples.rougether.userapi.furniture.ai.FurnitureAiClient;
import com.triples.rougether.userapi.furniture.config.FurnitureGenerationProperties;
import com.triples.rougether.userapi.furniture.service.FurnitureGenerationTransactions.Claim;
import com.triples.rougether.userapi.global.storage.AssetStorageService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class FurnitureGenerationWorker {
    private final FurnitureGenerationTransactions transactions;
    private final FurnitureAiClient ai;
    private final FurnitureImages images;
    private final AssetStorageService storage;
    private final FurnitureGenerationProperties config;

    public void runNext() {
        if (!ai.available()) return;
        for (String id : transactions.due()) {
            Claim claim = transactions.claim(id);
            if (claim == null) continue;
            process(claim);
            return;
        }
    }

    private void process(Claim claim) {
        String uploaded = null;
        boolean linked = false;
        try {
            byte[] source = read(claim.sourceKey());
            List<byte[]> references = config.styleReferenceKeys().stream().map(this::read).toList();
            byte[] candidate = claim.candidateKey() == null ? null : read(claim.candidateKey());
            var context = new FurnitureAiClient.Context(source, references, candidate,
                    claim.targetHint(), claim.feedback(), claim.correction());
            if (claim.action() != Action.REVIEW) {
                var generated = ai.generate(context, claim.action());
                var checked = images.candidate(generated.image());
                uploaded = storage.upload(checked.png(), "image/png", "private/furniture-generation/" + claim.id() + "/candidate");
                linked = transactions.generated(claim, uploaded, generated.inputTokens(), generated.outputTokens());
                if (linked) deletePrivate(claim.candidateKey());
            } else {
                var checked = images.candidate(candidate);
                var review = ai.review(context, checked.failures());
                String finalKey = null;
                if (review.decision() == FurnitureAiClient.Decision.ACCEPT && checked.passed()) {
                    if (Objects.equals(claim.candidateKey(), claim.resultAssetKey())) finalKey = claim.resultAssetKey();
                    else {
                        uploaded = storage.upload(checked.png(), "image/png", "items/photo-furniture/furniture");
                        finalKey = uploaded;
                    }
                }
                linked = transactions.reviewed(claim, review, checked.passed(), finalKey);
            }
        } catch (RuntimeException e) {
            String code = e instanceof FurnitureAiFailure failure ? failure.code() : "STORAGE_OR_PROCESSING_FAILED";
            transactions.failed(claim, code);
            log.warn("가구 생성 단계 실패 job={} action={} code={}", claim.id(), claim.action(), code);
        } finally {
            if (uploaded != null && !linked) {
                // 커밋 응답이 불확실한 경우 DB를 다시 확인함. 조회도 실패하면 삭제하지 않음.
                try { if (!transactions.referencesAsset(uploaded)) deleteQuietly(uploaded); }
                catch (RuntimeException e) { log.warn("가구 에셋 연결 확인 보류 job={}", claim.id()); }
            }
        }
    }

    public void maintain() {
        for (String id : transactions.maintenanceCandidates()) {
            try {
                var cleanup = transactions.maintain(id);
                if (cleanup == null) continue;
                // 성공한 공개 결과물은 보관함/방이 참조하므로 삭제하지 않음.
                if (cleanup.sourceKey() != null) storage.delete(cleanup.sourceKey());
                if (cleanup.candidateKey() != null && cleanup.candidateKey().startsWith("private/furniture-generation/")) {
                    storage.delete(cleanup.candidateKey());
                }
                transactions.cleaned(cleanup);
            } catch (RuntimeException e) { log.warn("가구 임시 사진 정리 보류 job={}", id); }
        }
    }

    private byte[] read(String key) {
        if (key == null) throw new FurnitureAiFailure("SOURCE_UNAVAILABLE");
        byte[] bytes = storage.read(key).content();
        if (bytes.length > FurnitureImages.MAX_BYTES) throw new FurnitureAiFailure("ASSET_TOO_LARGE");
        return bytes;
    }
    private void deletePrivate(String key) {
        if (key != null && key.startsWith("private/furniture-generation/")) deleteQuietly(key);
    }
    private void deleteQuietly(String key) {
        try { storage.delete(key); }
        catch (RuntimeException e) { log.warn("가구 미연결 에셋 정리 보류"); }
    }
}
