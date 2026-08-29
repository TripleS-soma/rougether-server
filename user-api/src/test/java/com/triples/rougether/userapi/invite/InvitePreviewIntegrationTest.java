package com.triples.rougether.userapi.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.invite.entity.UserInviteCode;
import com.triples.rougether.domain.invite.policy.InviteRewardPolicy;
import com.triples.rougether.domain.invite.repository.UserInviteCodeRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.policy.SignupWalletPolicy;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.userapi.invite.dto.InvitePreviewResponse;
import com.triples.rougether.userapi.invite.error.InviteErrorCode;
import com.triples.rougether.userapi.invite.service.InviteService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// 초대코드 미리보기(redeem 전 확인 화면) - 자동 입력된 코드의 검증 계약을 본다.
// 검증 순서·에러코드가 redeem 과 같아야 앱이 한 에러 집합만 다룬다.
@SpringBootTest
@Transactional
class InvitePreviewIntegrationTest {

    @Autowired private InviteService inviteService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserWalletRepository walletRepository;
    @Autowired private UserInviteCodeRepository userInviteCodeRepository;

    private User signUp(String email) {
        User user = userRepository.save(User.signUp(email));
        walletRepository.saveAll(SignupWalletPolicy.issueAll(user));
        return user;
    }

    @Test
    void 미리보기는_초대자_닉네임과_보상액을_돌려준다() {
        User inviter = signUp("preview-inviter@rougether.dev");
        inviter.changeNickname("소마루");
        User invitee = signUp("preview-invitee@rougether.dev");
        String code = inviteService.getMyCode(inviter.getId()).code();

        // 링크·클립보드에서 온 입력은 소문자·공백이 섞일 수 있다 - redeem 과 같은 정규화를 거쳐야 한다.
        InvitePreviewResponse res = inviteService.preview(invitee.getId(), "  " + code.toLowerCase() + " ");

        assertThat(res.inviterNickname()).isEqualTo("소마루");
        assertThat(res.inviteeRewardCoin()).isEqualTo(InviteRewardPolicy.INVITEE_REWARD_COIN);
        assertThat(res.alreadyRedeemed()).isFalse();
    }

    @Test
    void 닉네임_미설정_초대자는_null_로_내려간다() {
        User inviter = signUp("preview-noname-inviter@rougether.dev");
        User invitee = signUp("preview-noname-invitee@rougether.dev");
        String code = inviteService.getMyCode(inviter.getId()).code();

        assertThat(inviteService.preview(invitee.getId(), code).inviterNickname()).isNull();
    }

    @Test
    void 이미_보상을_받은_계정은_alreadyRedeemed_가_true_다() {
        User firstInviter = signUp("preview-redeemed-first@rougether.dev");
        User secondInviter = signUp("preview-redeemed-second@rougether.dev");
        User invitee = signUp("preview-redeemed-invitee@rougether.dev");
        inviteService.redeem(invitee.getId(), inviteService.getMyCode(firstInviter.getId()).code());

        InvitePreviewResponse res = inviteService.preview(
                invitee.getId(), inviteService.getMyCode(secondInviter.getId()).code());

        // 200 으로 내려 앱이 확인 화면을 건너뛰게 한다 - 실제 redeem 은 409 로 거절된다.
        assertThat(res.alreadyRedeemed()).isTrue();
    }

    @Test
    void 자기_코드는_미리보기도_거절한다() {
        User user = signUp("preview-self@rougether.dev");
        String code = inviteService.getMyCode(user.getId()).code();

        assertThatThrownBy(() -> inviteService.preview(user.getId(), code))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(InviteErrorCode.INVITE_SELF_NOT_ALLOWED);
    }

    @Test
    void 없는_코드는_NOT_FOUND_다() {
        User user = signUp("preview-notfound@rougether.dev");

        assertThatThrownBy(() -> inviteService.preview(user.getId(), "NOPE2345"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(InviteErrorCode.INVITE_CODE_NOT_FOUND);
    }

    @Test
    void 탈퇴한_초대자의_코드는_NOT_FOUND_다() {
        User inviter = signUp("preview-deleted-inviter@rougether.dev");
        User invitee = signUp("preview-deleted-invitee@rougether.dev");
        String code = inviteService.getMyCode(inviter.getId()).code();
        inviter.softDelete(Instant.now());

        assertThatThrownBy(() -> inviteService.preview(invitee.getId(), code))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(InviteErrorCode.INVITE_CODE_NOT_FOUND);
    }

    @Test
    void 봇_초대자의_코드는_거절한다() {
        // 봇은 getMyCode 가 거절하므로 코드가 생길 수 없지만, 방어선 확인을 위해 직접 심는다.
        User bot = userRepository.save(User.bot("preview-bot", "봇이", "봇 계정"));
        userInviteCodeRepository.save(UserInviteCode.issue(bot, "BOTC2345"));
        User invitee = signUp("preview-bot-invitee@rougether.dev");

        assertThatThrownBy(() -> inviteService.preview(invitee.getId(), "BOTC2345"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(InviteErrorCode.INVITE_BOT_NOT_ALLOWED);
    }

    @Test
    void 내_초대코드_응답에는_랜딩_공유_링크가_함께_내려간다() {
        User user = signUp("preview-share-url@rougether.dev");

        var res = inviteService.getMyCode(user.getId());

        // base URL 은 테스트 설정(invite.link.share-base-url) 값 - 미설정 동작은 InviteLinkUnconfiguredTest 참고.
        assertThat(res.shareUrl()).isEqualTo("https://invite.rougether.test/i/" + res.code());
    }
}
