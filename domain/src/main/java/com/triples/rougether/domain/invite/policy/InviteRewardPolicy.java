package com.triples.rougether.domain.invite.policy;

// 친구 초대 보상 정책.
// 보상액 50 = 가구 뽑기 단챠(25) 2회분이자 루틴·투두 일일 보상 상한(DailyRewardService, 50코인) 하루치.
// "하루 열심히 한 만큼"을 초대 1건에 얹는 크기로 잡아 체감은 주되, 부계정 어뷰징 시 피해가 커지지 않게 한다.
// 초대자 한도는 어뷰징 상한선이다 — 초대 자체는 계속 되지만 한도를 넘으면 초대자 몫만 0으로 지급한다.
public final class InviteRewardPolicy {

    public static final int INVITER_REWARD_COIN = 50;
    public static final int INVITEE_REWARD_COIN = 50;
    public static final int MAX_REWARDED_INVITES_PER_INVITER = 10;

    private InviteRewardPolicy() {
    }

    // 초대자가 이미 지급받은 건수 기준으로 이번 지급액을 정한다.
    public static int inviterRewardOf(long alreadyRewardedCount) {
        return alreadyRewardedCount < MAX_REWARDED_INVITES_PER_INVITER ? INVITER_REWARD_COIN : 0;
    }
}
