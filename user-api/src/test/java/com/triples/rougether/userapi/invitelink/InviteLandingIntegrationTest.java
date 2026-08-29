package com.triples.rougether.userapi.invitelink;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.invite.entity.InviteLinkClick;
import com.triples.rougether.domain.invite.entity.InviteLinkOs;
import com.triples.rougether.domain.invite.entity.InviteLinkType;
import com.triples.rougether.domain.invite.entity.UserInviteCode;
import com.triples.rougether.domain.invite.repository.InviteLinkClickRepository;
import com.triples.rougether.domain.invite.repository.UserInviteCodeRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.invitelink.service.InviteLandingService;
import com.triples.rougether.userapi.invitelink.service.InviteLandingView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// 초대 링크 랜딩의 코드 판정과 클릭 로그(퍼널 분모) 기록을 본다.
// 랜딩은 공개 경로라 무효 입력이 예외가 아닌 무효 상태 뷰로 떨어져야 한다.
@SpringBootTest
@Transactional
class InviteLandingIntegrationTest {

    @Autowired private InviteLandingService landingService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserInviteCodeRepository userInviteCodeRepository;
    @Autowired private HouseRepository houseRepository;
    @Autowired private InviteLinkClickRepository clickRepository;

    private User friendInviter(String email, String nickname, String code) {
        User inviter = userRepository.save(User.signUp(email));
        if (nickname != null) {
            inviter.changeNickname(nickname);
        }
        userInviteCodeRepository.save(UserInviteCode.issue(inviter, code));
        return inviter;
    }

    private List<InviteLinkClick> clicksOf(String code) {
        return clickRepository.findAll().stream()
                .filter(click -> code.equals(click.getCode()))
                .toList();
    }

    @Test
    void 유효한_친구_코드는_마스킹된_닉네임과_함께_유효_판정된다() {
        friendInviter("landing-friend@rougether.dev", "소마루", "FRND2345");

        // 링크 경로 입력은 소문자·공백이 섞일 수 있다.
        InviteLandingView view = landingService.resolveFriend(" frnd2345 ", InviteLinkOs.IOS);

        assertThat(view.valid()).isTrue();
        assertThat(view.code()).isEqualTo("FRND2345");
        // 공개 페이지라 닉네임 원문을 노출하지 않는다.
        assertThat(view.displayName()).isEqualTo("소**");

        List<InviteLinkClick> clicks = clicksOf("FRND2345");
        assertThat(clicks).hasSize(1);
        assertThat(clicks.get(0).getLinkType()).isEqualTo(InviteLinkType.FRIEND);
        assertThat(clicks.get(0).isValid()).isTrue();
        assertThat(clicks.get(0).getOs()).isEqualTo(InviteLinkOs.IOS);
    }

    @Test
    void 닉네임_미설정_초대자는_표시_이름_없이_유효_판정된다() {
        friendInviter("landing-noname@rougether.dev", null, "FRNN2345");

        InviteLandingView view = landingService.resolveFriend("FRNN2345", InviteLinkOs.ANDROID);

        assertThat(view.valid()).isTrue();
        assertThat(view.displayName()).isNull();
    }

    @Test
    void 없는_코드는_무효_판정과_함께_무효_클릭으로_기록된다() {
        InviteLandingView view = landingService.resolveFriend("NXPE2345", InviteLinkOs.OTHER);

        assertThat(view.valid()).isFalse();
        List<InviteLinkClick> clicks = clicksOf("NXPE2345");
        assertThat(clicks).hasSize(1);
        assertThat(clicks.get(0).isValid()).isFalse();
    }

    @Test
    void 탈퇴한_초대자의_코드는_무효_판정된다() {
        User inviter = friendInviter("landing-deleted@rougether.dev", "탈퇴자", "FRDD2345");
        inviter.softDelete(Instant.now());

        assertThat(landingService.resolveFriend("FRDD2345", InviteLinkOs.IOS).valid()).isFalse();
    }

    @Test
    void 봇_초대자의_코드는_무효_판정된다() {
        User bot = userRepository.save(User.bot("landing-bot", "봇이", "봇 계정"));
        userInviteCodeRepository.save(UserInviteCode.issue(bot, "FRBB2345"));

        assertThat(landingService.resolveFriend("FRBB2345", InviteLinkOs.IOS).valid()).isFalse();
    }

    @Test
    void 발급_문자_집합_밖_입력은_클릭_로그_없이_무효_판정된다() {
        long before = clickRepository.count();

        InviteLandingView tooShort = landingService.resolveFriend("ab", InviteLinkOs.OTHER);
        InviteLandingView badChars = landingService.resolveFriend("abc-def!", InviteLinkOs.OTHER);

        // 스캐너·오타 경로가 퍼널 분모를 오염시키지 않아야 하고, 입력 원문을 화면에 되돌려주지도 않는다.
        assertThat(tooShort.valid()).isFalse();
        assertThat(tooShort.code()).isNull();
        assertThat(badChars.code()).isNull();
        assertThat(clickRepository.count()).isEqualTo(before);
    }

    @Test
    void 한_글자_닉네임도_고정_길이로_마스킹된다() {
        friendInviter("landing-short@rougether.dev", "소", "FRSS2345");

        // 별표를 길이만큼 찍으면 1글자 닉네임이 원문 그대로 나가므로 고정 길이(첫 글자+**)로 가린다.
        assertThat(landingService.resolveFriend("FRSS2345", InviteLinkOs.IOS).displayName())
                .isEqualTo("소**");
    }

    @Test
    void 없는_집_코드는_무효_판정과_함께_무효_클릭으로_기록된다() {
        InviteLandingView view = landingService.resolveHouse("HZRA2345", InviteLinkOs.OTHER);

        assertThat(view.valid()).isFalse();
        assertThat(view.expired()).isFalse();
        assertThat(clicksOf("HZRA2345")).hasSize(1);
    }

    @Test
    void 유효한_집_코드는_집_이름과_함께_유효_판정된다() {
        User owner = userRepository.save(User.signUp("landing-house-owner@rougether.dev"));
        houseRepository.save(House.create(owner, "랜딩 테스트 집", null, null, 4, "HSEA2345",
                Instant.now().plus(Duration.ofDays(7))));

        InviteLandingView view = landingService.resolveHouse("hsea2345", InviteLinkOs.ANDROID);

        assertThat(view.valid()).isTrue();
        assertThat(view.displayName()).isEqualTo("랜딩 테스트 집");
        List<InviteLinkClick> clicks = clicksOf("HSEA2345");
        assertThat(clicks).hasSize(1);
        assertThat(clicks.get(0).getLinkType()).isEqualTo(InviteLinkType.HOUSE);
        assertThat(clicks.get(0).isValid()).isTrue();
    }

    @Test
    void 만료된_집_코드는_만료_상태로_판정되고_무효_클릭으로_기록된다() {
        User owner = userRepository.save(User.signUp("landing-house-expired@rougether.dev"));
        houseRepository.save(House.create(owner, "만료 테스트 집", null, null, 4, "HSEX2345",
                Instant.now().minus(Duration.ofDays(1))));

        InviteLandingView view = landingService.resolveHouse("HSEX2345", InviteLinkOs.IOS);

        assertThat(view.valid()).isFalse();
        assertThat(view.expired()).isTrue();
        assertThat(view.displayName()).isEqualTo("만료 테스트 집");
        assertThat(clicksOf("HSEX2345").get(0).isValid()).isFalse();
    }
}
