package com.triples.rougether.userapi.invite.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.invite.entity.InviteReward;
import com.triples.rougether.domain.invite.entity.UserInviteCode;
import com.triples.rougether.domain.invite.policy.InviteRewardPolicy;
import com.triples.rougether.domain.invite.repository.InviteRewardRepository;
import com.triples.rougether.domain.invite.repository.UserInviteCodeRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import com.triples.rougether.userapi.invite.dto.InviteRedeemResponse;
import com.triples.rougether.userapi.invite.dto.MyInviteCodeResponse;
import com.triples.rougether.userapi.invite.error.InviteErrorCode;
import com.triples.rougether.userapi.invite.support.UserInviteCodeGenerator;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 친구 초대 보상.
// 코드는 최초 조회 시 발급(lazy)한다 — 가입 트랜잭션을 건드리지 않아 기존 가입자도 자연히 커버된다.
// 지급은 초대자·초대받은 사람 양쪽이며, 중복 사용은 uq_invite_rewards_invitee 가 최후 방어선이다.
//
// 락 규칙(이 클래스 전체 불변식): 사용자 행 락을 id 오름차순으로 먼저 잡고, 지갑 락도 같은 순서로 잡는다.
// 서로를 동시에 초대하는 두 요청이 엇갈린 순서로 락을 잡으면 데드락이 되기 때문이다.
// 락 이후의 판정 조회는 반드시 locking read 로 한다 — REPEATABLE READ 에선 일반 조회가 트랜잭션
// 첫 읽기 시점의 스냅샷을 봐서, 락을 잡은 뒤에도 선행 커밋된 지급 이력을 못 본다.
@Service
@RequiredArgsConstructor
public class InviteService {

    private final UserInviteCodeRepository userInviteCodeRepository;
    private final InviteRewardRepository inviteRewardRepository;
    private final UserRepository userRepository;
    private final UserWalletRepository walletRepository;
    private final UserInviteCodeGenerator codeGenerator;

    @Transactional
    public MyInviteCodeResponse getMyCode(Long userId) {
        String code = userInviteCodeRepository.findByUserId(userId)
                .map(UserInviteCode::getCode)
                .orElseGet(() -> issueCode(userId));

        return new MyInviteCodeResponse(
                code,
                inviteRewardRepository.countByInviterIdAndInviterRewardAmountGreaterThan(userId, 0),
                InviteRewardPolicy.INVITER_REWARD_COIN,
                InviteRewardPolicy.INVITEE_REWARD_COIN,
                InviteRewardPolicy.MAX_REWARDED_INVITES_PER_INVITER);
    }

    // 초대코드 사용 - 코드 주인(초대자)과 사용자(초대받은 사람) 양쪽에 코인을 지급한다.
    @Transactional
    public InviteRedeemResponse redeem(Long inviteeUserId, String rawCode) {
        String code = normalize(rawCode);
        UserInviteCode inviteCode = userInviteCodeRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException(InviteErrorCode.INVITE_CODE_NOT_FOUND));

        Long inviterUserId = inviteCode.getUser().getId();
        if (inviterUserId.equals(inviteeUserId)) {
            throw new BusinessException(InviteErrorCode.INVITE_SELF_NOT_ALLOWED);
        }

        lockUsersInIdOrder(inviterUserId, inviteeUserId);

        if (inviteCode.getUser().isDeleted()) {
            throw new BusinessException(InviteErrorCode.INVITE_CODE_NOT_FOUND);
        }
        if (requireUser(inviteeUserId).isDeleted()) {
            throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
        }

        // 락 이후 재확인 - 동시 요청이 먼저 커밋했을 수 있다. 최종 방어선은 uq_invite_rewards_invitee.
        if (!inviteRewardRepository.findForUpdateByInviteeId(inviteeUserId).isEmpty()) {
            throw new BusinessException(InviteErrorCode.INVITE_ALREADY_REDEEMED);
        }

        int inviterReward = InviteRewardPolicy.inviterRewardOf(
                inviteRewardRepository.findRewardedForUpdateByInviterId(inviterUserId).size());
        int inviteeReward = InviteRewardPolicy.INVITEE_REWARD_COIN;

        UserWallet inviteeWallet = addCoinInIdOrder(inviterUserId, inviterReward, inviteeUserId, inviteeReward);

        inviteRewardRepository.save(InviteReward.of(
                userRepository.getReferenceById(inviterUserId),
                userRepository.getReferenceById(inviteeUserId),
                inviterReward,
                inviteeReward));

        return new InviteRedeemResponse(inviteeReward, inviteeWallet.getBalance(), inviterReward > 0);
    }

    // 최초 조회가 동시에 두 번 들어오면 둘 다 "없음"으로 보고 발급해 uq_user_invite_codes_user 를 위반한다.
    // 사용자 행 락으로 직렬화하고, 락 이후 locking read 로 선행 커밋을 확인한다.
    private String issueCode(Long userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_FOUND));
        return userInviteCodeRepository.findForUpdateByUserId(userId)
                .map(UserInviteCode::getCode)
                .orElseGet(() -> userInviteCodeRepository
                        .save(UserInviteCode.issue(user, codeGenerator.generate()))
                        .getCode());
    }

    // 코드는 대문자 집합으로만 발급되므로 입력을 대문자로 맞춰 대소문자 오입력을 흡수한다.
    private String normalize(String rawCode) {
        return rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
    }

    private void lockUsersInIdOrder(Long first, Long second) {
        Long smaller = Math.min(first, second);
        Long larger = Math.max(first, second);
        requireUser(smaller);
        requireUser(larger);
    }

    private User requireUser(Long userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_FOUND));
    }

    // 지갑 락도 사용자 id 오름차순으로 잡아 락 순서 불변식을 지갑 단계까지 유지한다.
    // 반환값은 초대받은 사람(invitee)의 지갑 — 응답의 잔액으로 쓴다.
    private UserWallet addCoinInIdOrder(Long inviterUserId, int inviterReward,
                                        Long inviteeUserId, int inviteeReward) {
        boolean inviterFirst = inviterUserId < inviteeUserId;
        Long firstId = inviterFirst ? inviterUserId : inviteeUserId;
        int firstAmount = inviterFirst ? inviterReward : inviteeReward;
        Long secondId = inviterFirst ? inviteeUserId : inviterUserId;
        int secondAmount = inviterFirst ? inviteeReward : inviterReward;

        UserWallet firstWallet = addCoin(firstId, firstAmount);
        UserWallet secondWallet = addCoin(secondId, secondAmount);
        return inviterFirst ? secondWallet : firstWallet;
    }

    // 코인 지갑은 가입 시 발급되지만, 이력이 없는 계정을 대비해 없으면 만들어 준다
    // (동시 발급은 uq_user_wallets_user_currency 가 막는다). 지급액 0(초대자 한도 초과)이면 적립만 건너뛴다.
    private UserWallet addCoin(Long userId, int amount) {
        UserWallet wallet = walletRepository.findWithLockByUserIdAndCurrencyType(userId, CurrencyType.COIN)
                .orElseGet(() -> walletRepository.save(
                        UserWallet.create(userRepository.getReferenceById(userId), CurrencyType.COIN)));
        if (amount > 0) {
            wallet.add(amount);
        }
        return wallet;
    }
}
