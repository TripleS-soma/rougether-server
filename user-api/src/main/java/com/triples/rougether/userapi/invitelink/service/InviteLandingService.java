package com.triples.rougether.userapi.invitelink.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.invite.entity.InviteLinkClick;
import com.triples.rougether.domain.invite.entity.InviteLinkOs;
import com.triples.rougether.domain.invite.entity.InviteLinkType;
import com.triples.rougether.domain.invite.repository.InviteLinkClickRepository;
import com.triples.rougether.domain.invite.repository.UserInviteCodeRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.userapi.house.dto.HousePreviewResponse;
import com.triples.rougether.userapi.house.service.HouseJoinService;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 초대 링크 랜딩(/i, /h)의 코드 판정 + 클릭 로그.
// 랜딩은 비인증 공개 경로라 판정 실패를 예외로 올리지 않고 무효 상태 뷰로 돌려준다(항상 200 렌더).
// 클릭 로그는 발급 문자 집합 형식에 맞는 코드만 남긴다 — 스캐너·오타 경로가 퍼널 분모를 오염시키지 않게.
@Service
@RequiredArgsConstructor
public class InviteLandingService {

    // 발급 문자 집합(영대문자+숫자, 혼동문자 제외) 기준의 느슨한 상한. 현재 발급 길이는 8이지만
    // 컬럼 길이(20)까지 허용해 발급 길이 변경에 로그가 끊기지 않게 한다.
    private static final Pattern CODE_FORMAT = Pattern.compile("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{4,20}");

    private final UserInviteCodeRepository userInviteCodeRepository;
    private final HouseJoinService houseJoinService;
    private final InviteLinkClickRepository clickRepository;

    @Transactional
    public InviteLandingView resolveFriend(String rawCode, InviteLinkOs os) {
        String code = normalize(rawCode);
        if (!CODE_FORMAT.matcher(code).matches()) {
            return InviteLandingView.invalidFormat(InviteLinkType.FRIEND);
        }
        InviteLandingView view = userInviteCodeRepository.findByCode(code)
                .map(inviteCode -> friendView(code, inviteCode.getUser()))
                .orElseGet(() -> InviteLandingView.invalid(InviteLinkType.FRIEND, code));
        recordClick(view, os);
        return view;
    }

    @Transactional
    public InviteLandingView resolveHouse(String rawCode, InviteLinkOs os) {
        String code = normalize(rawCode);
        if (!CODE_FORMAT.matcher(code).matches()) {
            return InviteLandingView.invalidFormat(InviteLinkType.HOUSE);
        }
        InviteLandingView view = houseView(code);
        recordClick(view, os);
        return view;
    }

    // 초대자가 탈퇴·봇이면 무효 — redeem 이 어차피 거부하는 코드로 설치를 유도하지 않는다.
    private InviteLandingView friendView(String code, User inviter) {
        if (inviter.isDeleted() || inviter.isBot()) {
            return InviteLandingView.invalid(InviteLinkType.FRIEND, code);
        }
        return InviteLandingView.valid(InviteLinkType.FRIEND, code, maskNickname(inviter.getNickname()));
    }

    // 집 코드 판정은 join-by-code 미리보기(HouseJoinService.preview)를 재사용한다.
    // 미리보기의 미존재 예외(INVITE_CODE_INVALID)는 랜딩에선 무효 상태 뷰로 흡수한다.
    private InviteLandingView houseView(String code) {
        try {
            HousePreviewResponse preview = houseJoinService.preview(code);
            if (preview.inviteExpired()) {
                return InviteLandingView.expired(InviteLinkType.HOUSE, code, preview.name());
            }
            return InviteLandingView.valid(InviteLinkType.HOUSE, code, preview.name());
        } catch (BusinessException e) {
            return InviteLandingView.invalid(InviteLinkType.HOUSE, code);
        }
    }

    private void recordClick(InviteLandingView view, InviteLinkOs os) {
        clickRepository.save(InviteLinkClick.of(view.type(), view.code(), view.valid(), os));
    }

    // 공개 랜딩에는 닉네임 원문을 노출하지 않는다 — 첫 글자만 남기고 마스킹.
    // 닉네임 미설정(온보딩 전)·익명화 계정은 null 로 돌려 렌더러가 일반 문구로 폴백하게 한다.
    private String maskNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return null;
        }
        String trimmed = nickname.trim();
        int codePoints = trimmed.codePointCount(0, trimmed.length());
        String first = trimmed.substring(0, trimmed.offsetByCodePoints(0, 1));
        return first + "*".repeat(codePoints - 1);
    }

    // 코드는 대문자 집합으로만 발급된다 — redeem·join-by-code 와 동일한 정규화 규칙.
    private String normalize(String rawCode) {
        return rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
    }
}
