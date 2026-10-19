package com.triples.rougether.userapi.furniture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Action;
import com.triples.rougether.furniture.ai.FurnitureAiClient;
import com.triples.rougether.furniture.ai.FurnitureAiClient.*;
import com.triples.rougether.furniture.config.FurnitureGenerationProperties;
import com.triples.rougether.furniture.lambda.FurnitureLambdaProtocol.Result;
import com.triples.rougether.furniture.lambda.FurnitureLambdaStage;
import com.triples.rougether.furniture.service.FurnitureAiFailure;
import com.triples.rougether.furniture.service.FurnitureGenerationTransactions.Claim;
import com.triples.rougether.furniture.service.FurnitureImages;
import com.triples.rougether.infra.assets.AssetStorageService;
import com.triples.rougether.infra.assets.StoredAsset;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

class FurnitureAiStageCompatibilityTest {
    private static final String SOURCE = "private/source.png";
    private static final String FIRST_REFERENCE = "items/first-reference.png";
    private static final String SECOND_REFERENCE = "items/second-reference.png";
    private static final String CANDIDATE = "private/furniture-generation/job/old-candidate.png";
    private static final String RESULT = "items/photo-furniture/existing.png";
    private static final String UPLOADED = "uploaded.png";
    private static final String EXECUTION_ID = "execution-2";

    private final FurnitureAiClient ai = mock(FurnitureAiClient.class);
    private final FurnitureImages images = mock(FurnitureImages.class);
    private final AssetStorageService storage = mock(AssetStorageService.class);
    private final FurnitureGenerationProperties config = new FurnitureGenerationProperties(
            true, "test", "test", Duration.ofSeconds(30), 3, 6, Duration.ofHours(24),
            List.of(FIRST_REFERENCE, SECOND_REFERENCE));
    private final byte[] source = {1};
    private final byte[] firstReference = {2};
    private final byte[] secondReference = {3};
    private final byte[] candidate = {4};
    private final byte[] generated = {5};
    private final byte[] normalized = {6};

    @BeforeEach
    void 입력_준비() {
        when(ai.available()).thenReturn(true);
        asset(SOURCE, source);
        asset(FIRST_REFERENCE, firstReference);
        asset(SECOND_REFERENCE, secondReference);
        asset(CANDIDATE, candidate);
    }

    @Test
    void 특징_추출에는_원본과_대상_힌트만_전달함() {
        Claim claim = claim(Action.EXTRACT);
        var extracted = new Extracted(true, "{\"kind\":\"chair\"}", 11, 12);
        when(ai.extract(source, claim.targetHint())).thenReturn(extracted);

        Result result = execute(claim);

        var order = inOrder(storage, ai);
        order.verify(storage).read(SOURCE);
        order.verify(ai).extract(source, "왼쪽 의자");
        verifyNoMoreInteractions(storage);
        verifyNoInteractions(images);
        verify(ai, never()).generate(any(), any());
        verify(ai, never()).review(any(), any());
        assertThat(result).isEqualTo(new Result(Action.EXTRACT, extracted, null, null, false, 0, 0, null));
    }

    @ParameterizedTest
    @EnumSource(value = Action.class, names = {"GENERATE", "EDIT", "REGENERATE"})
    void 생성에는_원본_없이_순서대로_기준_후보_메타데이터를_전달함(Action action) {
        Claim claim = claim(action);
        generated(claim, List.of());

        Result result = execute(claim);

        var context = ArgumentCaptor.forClass(Context.class);
        var order = inOrder(storage, ai, images);
        order.verify(storage).read(FIRST_REFERENCE);
        order.verify(storage).read(SECOND_REFERENCE);
        order.verify(storage).read(CANDIDATE);
        order.verify(ai).generate(context.capture(), eq(action));
        order.verify(images).candidate(generated);
        order.verify(storage).upload(normalized, "image/png", candidatePrefix());
        assertContext(context.getValue(), null, candidate);
        verify(storage, never()).read(SOURCE);
        assertGenerated(claim, result, true);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void 첫_생성은_후보가_없어도_실행함() {
        Claim claim = claim(Action.GENERATE, SOURCE, null, null);
        generated(claim, List.of());

        Result result = execute(claim);

        var context = ArgumentCaptor.forClass(Context.class);
        verify(ai).generate(context.capture(), eq(Action.GENERATE));
        assertContext(context.getValue(), null, null);
        verify(storage, never()).read(CANDIDATE);
        verify(storage, never()).read(isNull());
        assertGenerated(claim, result, true);
    }

    @Test
    void 생성된_후보는_하드_QA에_실패해도_검수를_위해_업로드함() {
        Claim claim = claim(Action.GENERATE);
        generated(claim, List.of("OBJECT_TOUCHES_CANVAS_BORDER"));

        Result result = execute(claim);

        verify(storage).upload(normalized, "image/png", candidatePrefix());
        assertGenerated(claim, result, false);
        verify(ai, never()).review(any(), any());
    }

    @Test
    void 검수는_원본_기준_후보_순서로_읽고_원래_후보와_하드_QA_실패를_전달함() {
        Claim claim = claim(Action.REVIEW);
        List<String> failures = List.of("BACKGROUND_NOT_REMOVED", "OBJECT_TOUCHES_CANVAS_BORDER");
        Review review = review(Decision.REJECT, failures);

        Result result = execute(claim);

        var context = ArgumentCaptor.forClass(Context.class);
        var order = inOrder(storage, images, ai);
        order.verify(storage).read(SOURCE);
        order.verify(storage).read(FIRST_REFERENCE);
        order.verify(storage).read(SECOND_REFERENCE);
        order.verify(storage).read(CANDIDATE);
        order.verify(images).candidate(candidate);
        order.verify(ai).review(context.capture(), eq(failures));
        assertContext(context.getValue(), source, candidate);
        verify(storage, never()).upload(any(), anyString(), anyString());
        assertReviewed(result, review, false, null);
    }

    @Test
    void AI가_승인해도_하드_QA에_실패하면_공개_결과를_업로드하지_않음() {
        Claim claim = claim(Action.REVIEW);
        Review review = review(Decision.ACCEPT, List.of("TRANSPARENT_BACKGROUND_REQUIRED"));

        Result result = execute(claim);

        verify(storage, never()).upload(any(), anyString(), anyString());
        assertReviewed(result, review, false, null);
    }

    @Test
    void 승인한_후보가_기존_결과면_같은_키를_재사용함() {
        Claim claim = claim(Action.REVIEW, SOURCE, RESULT, RESULT);
        asset(RESULT, candidate);
        Review review = review(Decision.ACCEPT, List.of());

        Result result = execute(claim);

        verify(storage, never()).upload(any(), anyString(), anyString());
        assertReviewed(result, review, true, RESULT);
    }

    @Test
    void 새로_승인한_후보는_정규화한_바이트를_실행별_경로에_업로드함() {
        Claim claim = claim(Action.REVIEW);
        Review review = review(Decision.ACCEPT, List.of());
        when(storage.upload(any(), anyString(), anyString())).thenReturn(UPLOADED);

        Result result = execute(claim);

        verify(storage).upload(normalized, "image/png", "items/photo-furniture/furniture/" + EXECUTION_ID);
        var context = ArgumentCaptor.forClass(Context.class);
        verify(ai).review(context.capture(), eq(List.of()));
        assertThat(context.getValue().candidate()).isSameAs(candidate);
        assertReviewed(result, review, true, UPLOADED);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void 원본_키가_없으면_AI를_호출하지_않음() {
        Claim claim = claim(Action.EXTRACT, null, null, null);

        assertFailure(claim, "SOURCE_UNAVAILABLE");

        verifyNoInteractions(storage, images);
        verify(ai, never()).extract(any(), anyString());
    }

    @Test
    void 정확히_10MB인_입력은_추출에_전달함() {
        Claim claim = claim(Action.EXTRACT);
        byte[] limit = new byte[FurnitureImages.MAX_BYTES];
        asset(SOURCE, limit);
        var extracted = new Extracted(true, "{}", 3, 4);
        when(ai.extract(limit, claim.targetHint())).thenReturn(extracted);

        Result result = execute(claim);

        verify(ai).extract(limit, claim.targetHint());
        assertThat(result.extracted()).isSameAs(extracted);
    }

    @Test
    void 입력이_10MB보다_한_바이트라도_크면_AI를_호출하지_않음() {
        Claim claim = claim(Action.EXTRACT);
        asset(SOURCE, new byte[FurnitureImages.MAX_BYTES + 1]);

        assertFailure(claim, "ASSET_TOO_LARGE");

        verify(ai, never()).extract(any(), anyString());
        verifyNoInteractions(images);
    }

    @Test
    void AI를_쓸_수_없으면_오류를_반환함() {
        when(ai.available()).thenReturn(false);

        assertThatThrownBy(() -> execute(claim(Action.GENERATE)))
                .isInstanceOf(FurnitureAiFailure.class).hasMessage("AI_UNAVAILABLE");

        verifyNoInteractions(storage, images);
        verify(ai).available();
        verifyNoMoreInteractions(ai);
    }

    private Result execute(Claim claim) {
        return new FurnitureLambdaStage(ai, images, storage, config).execute(claim, EXECUTION_ID);
    }

    private void generated(Claim claim, List<String> failures) {
        when(ai.generate(any(), eq(claim.action()))).thenReturn(new Generated(generated, 21, 22));
        when(images.candidate(generated)).thenReturn(new FurnitureImages.Candidate(normalized, failures));
        when(storage.upload(any(), anyString(), anyString())).thenReturn(UPLOADED);
    }

    private Review review(Decision decision, List<String> failures) {
        Review review = new Review(decision, "의자", "검수 이유", "보정 지시", 31, 32);
        when(images.candidate(candidate)).thenReturn(new FurnitureImages.Candidate(normalized, failures));
        when(ai.review(any(), eq(failures))).thenReturn(review);
        return review;
    }

    private void assertGenerated(Claim claim, Result result, boolean hardPass) {
        assertThat(result).isEqualTo(new Result(claim.action(), null, null, UPLOADED, hardPass, 21, 22, null));
    }

    private void assertReviewed(Result result, Review review, boolean hardPass, String key) {
        assertThat(result).isEqualTo(new Result(Action.REVIEW, null, review, key, hardPass, 0, 0, null));
    }

    private void assertFailure(Claim claim, String code) {
        assertThatThrownBy(() -> execute(claim)).isInstanceOf(FurnitureAiFailure.class).hasMessage(code);
    }

    private void assertContext(Context context, byte[] expectedSource, byte[] expectedCandidate) {
        assertThat(context.source()).isSameAs(expectedSource);
        assertThat(context.references()).containsExactly(firstReference, secondReference);
        assertThat(context.candidate()).isSameAs(expectedCandidate);
        assertThat(context.targetHint()).isEqualTo("왼쪽 의자");
        assertThat(context.feedback()).isEqualTo("등받이를 낮춰 주세요");
        assertThat(context.correction()).isEqualTo("테두리 여백 유지");
        assertThat(context.subjectJson()).isEqualTo("{\"kind\":\"chair\"}");
    }

    private void asset(String key, byte[] bytes) {
        when(storage.read(key)).thenReturn(new StoredAsset(bytes, "image/png"));
    }

    private Claim claim(Action action) {
        return claim(action, SOURCE, CANDIDATE, RESULT);
    }

    private Claim claim(Action action, String sourceKey, String candidateKey, String resultKey) {
        return new Claim("job", 7L, "lease-token", action, sourceKey, candidateKey, resultKey,
                "왼쪽 의자", "등받이를 낮춰 주세요", "테두리 여백 유지", "{\"kind\":\"chair\"}");
    }

    private String candidatePrefix() {
        return "private/furniture-generation/job/" + EXECUTION_ID + "/candidate";
    }
}
