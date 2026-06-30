package com.triples.rougether.userapi.auth.controller;

import com.triples.rougether.userapi.auth.service.AuthService;

import com.triples.rougether.userapi.auth.dto.DevLoginRequest;
import com.triples.rougether.userapi.auth.dto.LoginResponse;
import com.triples.rougether.userapi.auth.dto.LogoutRequest;
import com.triples.rougether.userapi.auth.dto.RefreshRequest;
import com.triples.rougether.userapi.auth.dto.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "인증 관련 API")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "개발용 로그인", description = "userId로 토큰을 발급하는 개발 전용 로그인입니다. 운영에서는 사용하지 않습니다.")
    @SecurityRequirements
    @PostMapping("/dev-login")
    public LoginResponse devLogin(@RequestBody DevLoginRequest request) {
        return authService.devLogin(request.userId());
    }

    @Operation(summary = "토큰 재발급", description = "refresh token으로 access/refresh token을 재발급합니다.")
    @SecurityRequirements
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @Operation(summary = "로그아웃", description = "전달한 refresh token을 폐기합니다.")
    @SecurityRequirements
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
    }
}
