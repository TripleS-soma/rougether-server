package com.triples.rougether.userapi.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.invite.policy.InviteRewardPolicy;
import com.triples.rougether.domain.invite.repository.InviteRewardRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.policy.SignupWalletPolicy;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.invite.dto.InviteRedeemResponse;
import com.triples.rougether.userapi.invite.dto.MyInviteCodeResponse;
import com.triples.rougether.userapi.invite.error.InviteErrorCode;
import com.triples.rougether.userapi.invite.service.InviteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// 초대 보상의 지급·중복방지·한도를 실제 DB(Testcontainers MySQL)로 검증한다.
// 재화가 걸린 경로라 "얼마가 실제로 지갑에 꽂혔는지"까지 본다.
// 단일 트랜잭션이라 락·커밋 경합은 검증할 수 없다 — 그쪽은 InviteRewardConcurrencyTest 가 맡는다.
@SpringBootTest
@Transactional
class InviteRewardIntegrationTest {

    @Autowired private InviteService inviteService;
    @Autowired private InviteRewardRepository inviteRewardRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserWalletRepository walletRepository;

    private User signUp(String email) {
        User user = userRepository.save(User.signUp(email));
        walletRepository.saveAll(SignupWalletPolicy.issueAll(user));
        return user;
    }

    private int coinBalance(Long userId) {
        return walletRepository.findWithLockByUserIdAndCurrencyType(userId, CurrencyType.COIN)
                .map(UserWallet::getBalance)
                .orElse(0);
    }

    @Test
    void 초대코드는_최초_조회_시_발급되고_같은_코드가_유지된다() {
        User user = signUp("invite-issue@rougether.dev");

        MyInviteCodeResponse first = inviteService.getMyCode(user.getId());
        MyInviteCodeResponse second = inviteService.getMyCode(user.getId());

        assertThat(first.code()).hasSize(8);
        // 혼동문자(I,O,L,0,1) 제외 문자 집합만 사용
        assertThat(first.code()).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}");
        assertThat(second.code()).isEqualTo(first.code());
        assertThat(first.rewardedCount()).isZero();
    }

    @Test
    void 초대코드를_쓰면_초대자와_초대받은_사람_양쪽에_코인이_지급된다() {
        User inviter = signUp("invite-inviter@rougether.dev");
        User invitee = signUp("invite-invitee@rougether.dev");
        String code = inviteService.getMyCode(inviter.getId()).code();
        int initial = SignupWalletPolicy.INITIAL_COIN_BALANCE;

        InviteRedeemResponse res = inviteService.redeem(invitee.getId(), code);

        assertThat(res.rewardCoin()).isEqualTo(InviteRewardPolicy.INVITEE_REWARD_COIN);
        assertThat(res.inviterRewarded()).isTrue();
        assertThat(res.coinBalance()).isEqualTo(initial + InviteRewardPolicy.INVITEE_REWARD_COIN);
        assertThat(coinBalance(inviter.getId())).isEqualTo(initial + InviteRewardPolicy.INVITER_REWARD_COIN);
        assertThat(inviteRewardRepository.countByInviterIdAndInviterRewardAmountGreaterThan(inviter.getId(), 0)).isEqualTo(1);
    }

    @Test
    void 소문자로_입력해도_대문자_코드로_인식한다() {
        User inviter = signUp("invite-lowercase-inviter@rougether.dev");
        User invitee = signUp("invite-lowercase-invitee@rougether.dev");
        String code = inviteService.getMyCode(inviter.getId()).code();

        InviteRedeemResponse res = inviteService.redeem(invitee.getId(), "  " + code.toLowerCase() + "  ");

        assertThat(res.rewardCoin()).isEqualTo(InviteRewardPolicy.INVITEE_REWARD_COIN);
    }

    @Test
    void 자기_코드는_쓸_수_없다() {
        User user = signUp("invite-self@rougether.dev");
        String code = inviteService.getMyCode(user.getId()).code();

        assertThatThrownBy(() -> inviteService.redeem(user.getId(), code))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(InviteErrorCode.INVITE_SELF_NOT_ALLOWED));
        assertThat(coinBalance(user.getId())).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE);
    }

    @Test
    void 없는_코드는_거부한다() {
        User invitee = signUp("invite-unknown@rougether.dev");

        assertThatThrownBy(() -> inviteService.redeem(invitee.getId(), "ZZZZZZZZ"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(InviteErrorCode.INVITE_CODE_NOT_FOUND));
    }

    @Test
    void 한_계정은_초대_보상을_한_번만_받는다() {
        User inviterA = signUp("invite-dup-a@rougether.dev");
        User inviterB = signUp("invite-dup-b@rougether.dev");
        User invitee = signUp("invite-dup-invitee@rougether.dev");
        String codeA = inviteService.getMyCode(inviterA.getId()).code();
        String codeB = inviteService.getMyCode(inviterB.getId()).code();

        inviteService.redeem(invitee.getId(), codeA);

        // 다른 사람의 코드로 한 번 더 시도해도 막힌다 — 코드별이 아니라 계정당 1회다.
        assertThatThrownBy(() -> inviteService.redeem(invitee.getId(), codeB))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(InviteErrorCode.INVITE_ALREADY_REDEEMED));
        assertThat(coinBalance(invitee.getId()))
                .isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE + InviteRewardPolicy.INVITEE_REWARD_COIN);
        assertThat(coinBalance(inviterB.getId())).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE);
    }

    @Test
    void 초대자_한도를_넘기면_초대자_몫만_0이_되고_초대받은_사람은_받는다() {
        User inviter = signUp("invite-cap-inviter@rougether.dev");
        String code = inviteService.getMyCode(inviter.getId()).code();
        int max = InviteRewardPolicy.MAX_REWARDED_INVITES_PER_INVITER;

        for (int i = 0; i < max; i++) {
            inviteService.redeem(signUp("invite-cap-" + i + "@rougether.dev").getId(), code);
        }
        int balanceAtCap = coinBalance(inviter.getId());

        User overflow = signUp("invite-cap-overflow@rougether.dev");
        InviteRedeemResponse res = inviteService.redeem(overflow.getId(), code);

        assertThat(res.inviterRewarded()).isFalse();
        assertThat(res.rewardCoin()).isEqualTo(InviteRewardPolicy.INVITEE_REWARD_COIN);
        assertThat(coinBalance(inviter.getId())).isEqualTo(balanceAtCap);
        assertThat(balanceAtCap).isEqualTo(
                SignupWalletPolicy.INITIAL_COIN_BALANCE + InviteRewardPolicy.INVITER_REWARD_COIN * max);
        // 한도를 넘겨도 이력은 남아야 중복 사용을 계속 막을 수 있다.
        assertThat(!inviteRewardRepository.findForUpdateByInviteeId(overflow.getId()).isEmpty()).isTrue();
    }
}
