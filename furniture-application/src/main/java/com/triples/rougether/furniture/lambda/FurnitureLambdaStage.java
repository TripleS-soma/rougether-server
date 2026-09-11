package com.triples.rougether.furniture.lambda;

import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Action;
import com.triples.rougether.furniture.ai.FurnitureAiClient;
import com.triples.rougether.furniture.config.FurnitureGenerationProperties;
import com.triples.rougether.furniture.service.*;
import com.triples.rougether.furniture.service.FurnitureGenerationTransactions.Claim;
import com.triples.rougether.infra.assets.AssetStorageService;
import java.util.Objects;
import lombok.RequiredArgsConstructor;

/** DB 의존성이 없는 한 단계 실행기. 호출 결과가 불확실하면 자동 재실행하지 않음. */
@RequiredArgsConstructor
public class FurnitureLambdaStage {
    private final FurnitureAiClient ai;
    private final FurnitureImages images;
    private final AssetStorageService storage;
    private final FurnitureGenerationProperties config;
    public FurnitureLambdaProtocol.Result execute(Claim claim, String executionId) {
        if (!ai.available()) throw new FurnitureAiFailure("AI_UNAVAILABLE");
        if (claim.action() == Action.EXTRACT) {
            return new FurnitureLambdaProtocol.Result(claim.action(), ai.extract(read(claim.sourceKey()), claim.targetHint()),
                    null, null, false, 0, 0, null);
        }
        byte[] source = claim.action() == Action.REVIEW ? read(claim.sourceKey()) : null;
        var references = config.styleReferenceKeys().stream().map(this::read).toList();
        byte[] candidate = claim.candidateKey() == null ? null : read(claim.candidateKey());
        var context = new FurnitureAiClient.Context(source, references, candidate, claim.targetHint(),
                claim.feedback(), claim.correction(), claim.subjectJson());
        if (claim.action() != Action.REVIEW) {
            var generated = ai.generate(context, claim.action());
            var checked = images.candidate(generated.image());
            String key = storage.upload(checked.png(), "image/png", "private/furniture-generation/" + claim.id()
                    + "/" + executionId + "/candidate");
            return new FurnitureLambdaProtocol.Result(claim.action(), null, null, key, checked.passed(),
                    generated.inputTokens(), generated.outputTokens(), null);
        }
        var checked = images.candidate(candidate);
        var review = ai.review(context, checked.failures());
        String key = null;
        if (review.decision() == FurnitureAiClient.Decision.ACCEPT && checked.passed()) {
            key = Objects.equals(claim.candidateKey(), claim.resultAssetKey()) ? claim.resultAssetKey()
                    : storage.upload(checked.png(), "image/png", "items/photo-furniture/furniture/" + executionId);
        }
        return new FurnitureLambdaProtocol.Result(claim.action(), null, review, key, checked.passed(), 0, 0, null);
    }
    private byte[] read(String key) {
        if (key == null) throw new FurnitureAiFailure("SOURCE_UNAVAILABLE");
        byte[] bytes = storage.read(key).content();
        if (bytes.length > FurnitureImages.MAX_BYTES) throw new FurnitureAiFailure("ASSET_TOO_LARGE");
        return bytes;
    }
}
