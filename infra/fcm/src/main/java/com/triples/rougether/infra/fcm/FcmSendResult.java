package com.triples.rougether.infra.fcm;

import java.util.List;

// successCount: 실제 전송에 성공한 토큰 수. invalidTokens: 등록 해제된(UNREGISTERED)/형식이 잘못된
// (INVALID_ARGUMENT) 토큰 - 삭제 대상. 배치 자체가 예외로 실패하거나 일시적 오류(UNAVAILABLE 등)인
// 토큰은 invalidTokens에 담기지 않지만 successCount에도 포함되지 않는다(삭제하지 않되 실패로는 집계).
public record FcmSendResult(int successCount, List<String> invalidTokens) {

    public static FcmSendResult empty() {
        return new FcmSendResult(0, List.of());
    }
}
