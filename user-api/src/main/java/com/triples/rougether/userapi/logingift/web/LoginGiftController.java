package com.triples.rougether.userapi.logingift.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.logingift.dto.LoginGiftListResponse;
import com.triples.rougether.userapi.logingift.service.LoginGiftReceiptService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events/login-gifts")
@RequiredArgsConstructor
public class LoginGiftController {
    private final LoginGiftReceiptService receipts;

    @Operation(summary = "내 접속 선물 지급 내역 조회", description = "grantedAt이 있으면 지급 완료입니다. acknowledgedAt이 없으면 수령 안내를 표시할 수 있습니다.")
    @GetMapping
    public LoginGiftListResponse list(@CurrentUser AuthUser user) {
        return receipts.list(user.id());
    }

    @Operation(summary = "접속 선물 수령 안내 확인", description = "지급 API가 아닙니다. 지급 완료된 내 선물의 안내 확인 시각만 멱등 기록합니다.")
    @PostMapping("/{participationId}/acknowledgement")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acknowledge(@CurrentUser AuthUser user, @PathVariable Long participationId) {
        receipts.acknowledge(user.id(), participationId);
    }
}
