package com.triples.rougether.userapi.appicon.web;

import com.triples.rougether.userapi.appicon.dto.AppIconResponse;
import com.triples.rougether.userapi.appicon.service.AppIconService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "AppIcon", description = "고양이 앱 아이콘 상태와 실제 앱 활동")
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class AppIconController {

    private final AppIconService appIconService;

    @Operation(summary = "내 앱 아이콘 상태 조회",
            description = "조회만으로 접속 시각을 갱신하지 않습니다. 루틴·투두 완료/취소 후 다시 조회합니다.")
    @GetMapping("/app-icon")
    public ResponseEntity<AppIconResponse> get(@CurrentUser AuthUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(appIconService.get(user.id()));
    }

    @Operation(summary = "실제 앱 foreground 활동 기록",
            description = "앱 진입/복귀 및 실제 foreground 사용 중에만 호출합니다. "
                    + "본문 없이 서버 시각으로 기록하며 미접속 상태와 알림 회차를 초기화합니다. "
                    + "푸시 수신·background fetch·토큰 refresh에서는 호출하지 않습니다.")
    @PostMapping("/app-activity")
    public ResponseEntity<AppIconResponse> recordForeground(@CurrentUser AuthUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(appIconService.recordForeground(user.id()));
    }
}
