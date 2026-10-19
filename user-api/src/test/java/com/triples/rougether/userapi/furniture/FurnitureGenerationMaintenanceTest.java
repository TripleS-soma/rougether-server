package com.triples.rougether.userapi.furniture;

import static org.mockito.Mockito.*;

import com.triples.rougether.furniture.service.FurnitureGenerationMaintenanceService;
import com.triples.rougether.furniture.service.FurnitureGenerationTransactions;
import com.triples.rougether.furniture.service.FurnitureGenerationTransactions.Cleanup;
import com.triples.rougether.infra.assets.AssetStorageService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FurnitureGenerationMaintenanceTest {
    private static final String SOURCE = "private/furniture-generation/job/source.png";
    private static final String RAW = "private/furniture-generation/job/raw.png";
    private static final String CANDIDATE = "private/furniture-generation/job/candidate.png";
    private static final String PUBLIC_RESULT = "items/photo-furniture/furniture/result.png";

    private final FurnitureGenerationTransactions transactions = mock(FurnitureGenerationTransactions.class);
    private final AssetStorageService storage = mock(AssetStorageService.class);
    private final Runnable maintenance = new FurnitureGenerationMaintenanceService(transactions, storage)::maintain;

    @Test
    void 원본과_업로드_원본과_비공개_후보를_지운_뒤_정리_완료를_기록함() {
        var cleanup = new Cleanup("job", SOURCE, CANDIDATE, RAW);
        when(transactions.maintenanceCandidates()).thenReturn(List.of("job"));
        when(transactions.maintain("job")).thenReturn(cleanup);

        maintenance.run();

        var order = inOrder(transactions, storage);
        order.verify(transactions).maintenanceCandidates();
        order.verify(transactions).maintain("job");
        order.verify(storage).delete(SOURCE);
        order.verify(storage).delete(RAW);
        order.verify(storage).delete(CANDIDATE);
        order.verify(transactions).cleaned(cleanup);
        verifyNoMoreInteractions(transactions, storage);
    }

    @Test
    void 공개_결과_이미지는_보존하고_임시_원본만_정리함() {
        var cleanup = new Cleanup("job", SOURCE, PUBLIC_RESULT, RAW);
        when(transactions.maintenanceCandidates()).thenReturn(List.of("job"));
        when(transactions.maintain("job")).thenReturn(cleanup);

        maintenance.run();

        verify(storage).delete(SOURCE);
        verify(storage).delete(RAW);
        verify(storage, never()).delete(PUBLIC_RESULT);
        verify(transactions).cleaned(cleanup);
        verifyNoMoreInteractions(storage);
    }

    @Test
    void 정리할_결과가_없으면_건너뛰고_다음_작업의_빈_에셋은_삭제_없이_완료함() {
        var empty = new Cleanup("empty", null, null, null);
        when(transactions.maintenanceCandidates()).thenReturn(List.of("pending", "empty"));
        when(transactions.maintain("pending")).thenReturn(null);
        when(transactions.maintain("empty")).thenReturn(empty);

        maintenance.run();

        var order = inOrder(transactions);
        order.verify(transactions).maintenanceCandidates();
        order.verify(transactions).maintain("pending");
        order.verify(transactions).maintain("empty");
        order.verify(transactions).cleaned(empty);
        verifyNoMoreInteractions(transactions);
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {SOURCE, RAW, CANDIDATE})
    void 삭제가_실패하면_완료를_기록하지_않고_다음_작업을_처리한_뒤_재호출에서_재시도함(String failedKey) {
        var cleanup = new Cleanup("job", SOURCE, CANDIDATE, RAW);
        var next = new Cleanup("next", null, PUBLIC_RESULT, null);
        when(transactions.maintenanceCandidates()).thenReturn(List.of("job", "next"), List.of("job"));
        when(transactions.maintain("job")).thenReturn(cleanup);
        when(transactions.maintain("next")).thenReturn(next);
        doThrow(new IllegalStateException("storage unavailable")).doNothing().when(storage).delete(failedKey);

        maintenance.run();

        verify(transactions, never()).cleaned(cleanup);
        verify(transactions).cleaned(next);
        verify(storage, never()).delete(PUBLIC_RESULT);

        clearInvocations(transactions, storage);
        maintenance.run();

        var order = inOrder(transactions, storage);
        order.verify(transactions).maintenanceCandidates();
        order.verify(transactions).maintain("job");
        order.verify(storage).delete(SOURCE);
        order.verify(storage).delete(RAW);
        order.verify(storage).delete(CANDIDATE);
        order.verify(transactions).cleaned(cleanup);
        verifyNoMoreInteractions(transactions, storage);
    }

    @Test
    void 완료_기록이_실패해도_다음_작업을_처리하고_재호출에서_삭제와_완료_기록을_재시도함() {
        var cleanup = new Cleanup("job", SOURCE, CANDIDATE, RAW);
        var next = new Cleanup("next", null, PUBLIC_RESULT, null);
        when(transactions.maintenanceCandidates()).thenReturn(List.of("job", "next"), List.of("job"));
        when(transactions.maintain("job")).thenReturn(cleanup);
        when(transactions.maintain("next")).thenReturn(next);
        doThrow(new IllegalStateException("database unavailable")).doNothing().when(transactions).cleaned(cleanup);

        maintenance.run();

        var order = inOrder(transactions, storage);
        order.verify(storage).delete(SOURCE);
        order.verify(storage).delete(RAW);
        order.verify(storage).delete(CANDIDATE);
        order.verify(transactions).cleaned(cleanup);
        order.verify(transactions).maintain("next");
        order.verify(transactions).cleaned(next);

        clearInvocations(transactions, storage);
        maintenance.run();

        var retry = inOrder(transactions, storage);
        retry.verify(transactions).maintenanceCandidates();
        retry.verify(transactions).maintain("job");
        retry.verify(storage).delete(SOURCE);
        retry.verify(storage).delete(RAW);
        retry.verify(storage).delete(CANDIDATE);
        retry.verify(transactions).cleaned(cleanup);
        verifyNoMoreInteractions(transactions, storage);
    }
}
