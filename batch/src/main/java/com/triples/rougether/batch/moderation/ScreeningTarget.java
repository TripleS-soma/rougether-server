package com.triples.rougether.batch.moderation;

import com.triples.rougether.domain.moderation.repository.ScreeningCandidate;

// 한 실행의 판정 대상(#479). reported 는 사용자 신고로 대기열에 이미 올라온 대상 — 점수만 붙이고 자동 신고는 만들지 않음.
public record ScreeningTarget(ScreeningCandidate candidate, boolean reported) {

    String key() {
        return candidate.getTargetType() + ":" + candidate.getTargetId();
    }
}
