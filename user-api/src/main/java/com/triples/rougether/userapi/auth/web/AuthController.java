package com.triples.rougether.userapi.auth.web;

import com.triples.rougether.userapi.auth.service.AuthService;

import com.triples.rougether.userapi.auth.dto.DevLoginRequest;
import com.triples.rougether.userapi.auth.dto.GoogleLoginRequest;
import com.triples.rougether.userapi.auth.dto.KakaoLoginRequest;
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

    @Operation(summary = "개발용 로그인", description = "userId로 토큰을 발급하는 개발 전용 로그인입니다. userId를 생략하면 새 회원을 생성해 로그인하며, 이때 통화별 지갑(COIN·DIAMOND)도 함께 발급합니다. 운영에서는 사용하지 않습니다.")
    @SecurityRequirements
    @PostMapping("/dev-login")
    public LoginResponse devLogin(@RequestBody DevLoginRequest request) {
        return authService.devLogin(request.userId());
    }

    @Operation(summary = "카카오 로그인", description = "카카오 access token으로 로그인합니다. 서버가 카카오에 토큰을 검증한 뒤 카카오 계정 기준으로 회원을 찾습니다. 최초 로그인이면 회원과 통화별 지갑(COIN·DIAMOND)을 자동 생성하고 카카오 계정을 연동하며, 카카오가 이메일을 제공한 경우 함께 저장합니다. 신규 가입 여부는 응답의 isNewUser로 구분합니다. 로그인 성공 시 마지막 로그인 시각이 갱신됩니다.")
    @SecurityRequirements
    @PostMapping("/kakao")
    public LoginResponse kakaoLogin(@Valid @RequestBody KakaoLoginRequest request) {
        return authService.kakaoLogin(request.accessToken());
    }

    @Operation(summary = "구글 로그인", description = "구글 id token으로 로그인합니다. 서버가 토큰의 서명·발급자·대상·만료를 검증한 뒤 구글 계정 기준으로 회원을 찾습니다. 최초 로그인이면 회원과 통화별 지갑(COIN·DIAMOND)을 자동 생성하고 구글 계정을 연동하며, 구글이 이메일을 제공한 경우 함께 저장합니다. 신규 가입 여부는 응답의 isNewUser로 구분합니다. 로그인 성공 시 마지막 로그인 시각이 갱신됩니다.")
    @SecurityRequirements
    @PostMapping("/google")
    public LoginResponse googleLogin(@Valid @RequestBody GoogleLoginRequest request) {
        return authService.googleLogin(request.idToken());
    }

    @Operation(summary = "토큰 재발급", description = "refresh token으로 access/refresh token을 재발급합니다. refresh token은 1회용으로, 재발급에 사용한 토큰은 즉시 폐기되고 새 refresh token으로 교체됩니다(rotation). 이후 요청에는 응답으로 받은 새 토큰 쌍을 사용합니다. access token 유효기간은 30분, refresh token 유효기간은 14일입니다.")
    @SecurityRequirements
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @Operation(summary = "로그아웃", description = "전달한 refresh token을 폐기합니다. 이미 폐기됐거나 존재하지 않는 토큰이어도 성공으로 처리합니다. access token은 만료 시각까지 계속 유효하므로 클라이언트에 저장한 토큰을 함께 삭제합니다.")
    @SecurityRequirements
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
    }
}
