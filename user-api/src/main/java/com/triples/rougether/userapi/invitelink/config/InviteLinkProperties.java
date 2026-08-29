package com.triples.rougether.userapi.invitelink.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 초대 링크/딥링크 연결값. 전부 미설정이어도 서버는 뜨며 기능이 단계적으로 줄어든다:
// share-base-url 미설정 → 응답 shareUrl null(앱은 코드만 공유), 스토어값 미설정 → 랜딩에서 해당 버튼 숨김,
// apple-app-id·android 서명값 미설정 → 해당 well-known 404(universal/app link 미검증 상태).
@ConfigurationProperties("invite.link")
public record InviteLinkProperties(
        String shareBaseUrl,
        String appScheme,
        String androidPackage,
        List<String> androidCertFingerprints,
        String appstoreId,
        String appleAppId) {

    public boolean hasShareBaseUrl() {
        return isSet(shareBaseUrl);
    }

    public boolean hasAppScheme() {
        return isSet(appScheme);
    }

    public boolean hasAndroidPackage() {
        return isSet(androidPackage);
    }

    public boolean hasAppstoreId() {
        return isSet(appstoreId);
    }

    public boolean hasAppleAppId() {
        return isSet(appleAppId);
    }

    public boolean hasAndroidCertFingerprints() {
        return androidCertFingerprints != null
                && androidCertFingerprints.stream().anyMatch(InviteLinkProperties::isSet);
    }

    // 링크 조립용 base URL — 뒤 슬래시를 정리해 "//i/CODE" 같은 이중 슬래시를 막는다.
    public String normalizedShareBaseUrl() {
        String base = shareBaseUrl;
        while (base != null && base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
