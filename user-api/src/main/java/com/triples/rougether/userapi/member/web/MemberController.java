package com.triples.rougether.userapi.member.web;

import com.triples.rougether.userapi.member.service.MemberService;
import com.triples.rougether.userapi.member.service.MemberWithdrawalService;
import com.triples.rougether.userapi.member.dto.MeResponse;
import com.triples.rougether.userapi.member.dto.MemberUpdateRequest;
import com.triples.rougether.userapi.member.dto.ProfileImageResponse;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Member", description = "회원 관련 API")
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;
    private final MemberWithdrawalService memberWithdrawalService;

    @Operation(summary = "내 정보 조회", description = "로그인한 회원의 기본 정보와 온보딩 진행 요약을 반환합니다. onboarding.completed로 온보딩 화면 진입 여부를 판단할 수 있습니다. lastAccessedAt은 UTC(Z) 기준이며 로그인 또는 refresh 재발급 성공 시 갱신됩니다.")
    @GetMapping
    public MeResponse me(@CurrentUser AuthUser authUser) {
        return memberService.getMe(authUser.id());
    }

    @Operation(summary = "회원정보 수정", description = "로그인한 회원의 닉네임을 수정하고 갱신된 내 정보를 반환합니다.")
    @PutMapping
    public MeResponse updateMe(@CurrentUser AuthUser authUser,
                               @Valid @RequestBody MemberUpdateRequest request) {
        return memberService.updateMe(authUser.id(), request);
    }

    @Operation(summary = "프로필 사진 등록·교체", description = "multipart file 필드로 프로필 사진을 업로드하고 발급된 asset key를 반환합니다. "
            + "png·jpeg·webp 형식, 10MB 이하만 허용됩니다. 기존 사진이 있으면 새 사진으로 교체됩니다.")
    @PutMapping(value = "/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProfileImageResponse updateProfileImage(@CurrentUser AuthUser authUser,
                                                   @RequestParam("file") MultipartFile file) {
        return memberService.updateProfileImage(authUser.id(), file);
    }

    @Operation(summary = "프로필 사진 삭제", description = "프로필 사진을 삭제합니다. 삭제 후 내 정보 조회(GET /api/v1/me)의 profileImageKey는 null로 반환되며 기본 이미지를 표시합니다.")
    @DeleteMapping("/profile-image")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProfileImage(@CurrentUser AuthUser authUser) {
        memberService.deleteProfileImage(authUser.id());
    }

    @Operation(summary = "회원탈퇴", description = "로그인한 회원을 탈퇴 처리합니다. 계정이 삭제되고 개인정보(이메일·닉네임·소개글·프로필 사진)가 즉시 파기되며, 루틴·할 일·카테고리가 삭제되고 refresh token이 모두 폐기되며 소셜 로그인 연동이 해제됩니다. 같은 소셜 계정으로 다시 로그인하면 새 계정으로 가입되며 기존 데이터는 복원되지 않습니다. 이미 발급된 access token은 만료 시각까지 유효하므로 클라이언트에 저장한 토큰을 함께 삭제합니다.")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@CurrentUser AuthUser authUser) {
        memberWithdrawalService.withdraw(authUser.id());
    }
}
