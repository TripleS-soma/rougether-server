package com.triples.rougether.userapi.invitelink.service;

import com.triples.rougether.domain.invite.entity.InviteLinkType;

// 랜딩 렌더링에 필요한 판정 결과.
// code 는 정규화(trim+대문자)된 값이며, 발급 문자 집합 형식에 맞을 때만 채워진다(형식 밖 입력은 화면에 되돌려주지 않음).
// displayName 은 FRIEND 면 마스킹된 초대자 닉네임(미설정이면 null), HOUSE 면 집 이름.
public record InviteLandingView(
        InviteLinkType type,
        String code,
        boolean valid,
        boolean expired,
        String displayName) {

    public static InviteLandingView invalidFormat(InviteLinkType type) {
        return new InviteLandingView(type, null, false, false, null);
    }

    public static InviteLandingView invalid(InviteLinkType type, String code) {
        return new InviteLandingView(type, code, false, false, null);
    }

    public static InviteLandingView expired(InviteLinkType type, String code, String displayName) {
        return new InviteLandingView(type, code, false, true, displayName);
    }

    public static InviteLandingView valid(InviteLinkType type, String code, String displayName) {
        return new InviteLandingView(type, code, true, false, displayName);
    }
}
