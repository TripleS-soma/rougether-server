package com.triples.rougether.domain.moderation.repository;

// 자동 유해 판정 후보(#478) 네이티브 조회 projection. context 는 댓글의 원 게시글 앞부분(글이면 null).
public interface ScreeningCandidate {

    String getTargetType();

    Long getTargetId();

    Long getOwnerUserId();

    String getContent();

    String getContext();
}
