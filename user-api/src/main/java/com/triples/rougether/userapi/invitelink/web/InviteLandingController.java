package com.triples.rougether.userapi.invitelink.web;

import com.triples.rougether.domain.invite.entity.InviteLinkOs;
import com.triples.rougether.userapi.invitelink.service.InviteLandingService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.stereotype.Controller;

// 초대 링크 랜딩 — 앱 미설치 사용자가 초대 링크를 눌렀을 때 보는 공개 페이지(비인증, SecurityConfig permitAll).
// 설치된 기기에서는 이 URL 이 universal/app link 로 앱을 직접 열고(well-known 검증 전제), 이 페이지는 폴백이다.
// 무효 코드도 200 으로 렌더한다 — 스토어 이동 자체는 막을 이유가 없다. API 스펙이 아니라 @Hidden.
@Hidden
@Controller
@RequiredArgsConstructor
public class InviteLandingController {

    private final InviteLandingService inviteLandingService;
    private final InviteLandingPageRenderer renderer;

    // 한글 페이지라 charset 을 명시해 컨버터 기본값 변화에 흔들리지 않게 한다.
    private static final String TEXT_HTML_UTF8 = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8";

    // 친구 초대(개인 초대코드) 랜딩. 코드 종류는 path 로 구분한다 — 두 코드 네임스페이스는 겹칠 수 있다.
    @GetMapping(value = "/i/{code}", produces = TEXT_HTML_UTF8)
    @ResponseBody
    public String friendLanding(@PathVariable String code,
                                @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return renderer.render(inviteLandingService.resolveFriend(code, InviteLinkOs.fromUserAgent(userAgent)));
    }

    // 집 초대코드(집 공용·구성원 개인) 랜딩.
    @GetMapping(value = "/h/{code}", produces = TEXT_HTML_UTF8)
    @ResponseBody
    public String houseLanding(@PathVariable String code,
                               @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return renderer.render(inviteLandingService.resolveHouse(code, InviteLinkOs.fromUserAgent(userAgent)));
    }
}
