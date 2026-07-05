package com.triples.rougether.adminapi.wallet.web;

import com.triples.rougether.adminapi.wallet.dto.WalletGrantRequest;
import com.triples.rougether.adminapi.wallet.dto.WalletGrantResponse;
import com.triples.rougether.adminapi.wallet.service.WalletGrantService;
import com.triples.rougether.common.error.ErrorResponse;
import jakarta.validation.Valid;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 개발/QA용 재화 지급. 어드민 인증 필요, curl/스크립트 호출용(CSRF 제외).
@RestController
@RequestMapping("/admin/users/{userId}/wallets")
public class WalletGrantController {

    private final WalletGrantService walletGrantService;

    public WalletGrantController(WalletGrantService walletGrantService) {
        this.walletGrantService = walletGrantService;
    }

    @PostMapping("/grant")
    public WalletGrantResponse grant(@PathVariable Long userId,
                                     @Valid @RequestBody WalletGrantRequest request) {
        return walletGrantService.grant(userId, request);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("USER_NOT_FOUND", exception.getMessage()));
    }
}
