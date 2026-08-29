package com.triples.rougether.domain.invite.entity;

// 초대 링크 클릭 시 User-Agent 로 추정한 OS 분류. 원문 UA 는 저장하지 않는다.
public enum InviteLinkOs {
    ANDROID,
    IOS,
    OTHER;

    // 판별 규칙: iPhone/iPad/iPod → IOS, Android → ANDROID, 그 외/누락 → OTHER.
    // iPad 데스크톱 모드(Macintosh UA)는 OTHER 로 떨어짐 — 지표 추정치로만 쓰므로 허용.
    public static InviteLinkOs fromUserAgent(String userAgent) {
        if (userAgent == null) {
            return OTHER;
        }
        if (userAgent.contains("iPhone") || userAgent.contains("iPad") || userAgent.contains("iPod")) {
            return IOS;
        }
        if (userAgent.contains("Android")) {
            return ANDROID;
        }
        return OTHER;
    }
}
