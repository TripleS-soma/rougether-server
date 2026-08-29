package com.triples.rougether.userapi.invitelink.web;

import com.triples.rougether.userapi.invitelink.config.InviteLinkProperties;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// universal link(iOS)·app link(Android) 검증 파일 서빙. 링크 도메인(share-base-url 호스트)이 이 앱을
// 가리킬 때 OS 가 설치 여부 판단에 사용한다. 연결값 미설정이면 404 — 잘못된 자리표시자 검증 파일을
// 배포해 링크 열기가 어중간하게 걸리는 것보다 명시적으로 없는 상태가 낫다.
@Hidden
@RestController
@RequiredArgsConstructor
public class WellKnownController {

    private final InviteLinkProperties properties;

    // 애플 규격상 확장자 없는 경로에 application/json 으로 내려야 한다.
    @GetMapping(value = "/.well-known/apple-app-site-association", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> appleAppSiteAssociation() {
        if (!properties.hasAppleAppId()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> detail = Map.of(
                "appIDs", List.of(properties.appleAppId()),
                "components", List.of(Map.of("/", "/i/*"), Map.of("/", "/h/*")));
        return ResponseEntity.ok(Map.of(
                "applinks", Map.of("apps", List.of(), "details", List.of(detail))));
    }

    @GetMapping(value = "/.well-known/assetlinks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> assetLinks() {
        if (!properties.hasAndroidPackage() || !properties.hasAndroidCertFingerprints()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(List.of(Map.of(
                "relation", List.of("delegate_permission/common.handle_all_urls"),
                "target", Map.of(
                        "namespace", "android_app",
                        "package_name", properties.androidPackage(),
                        "sha256_cert_fingerprints", properties.androidCertFingerprints()))));
    }
}
