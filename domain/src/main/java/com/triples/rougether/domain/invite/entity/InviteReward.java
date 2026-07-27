package com.triples.rougether.domain.invite.entity;

import com.triples.rougether.domain.member.entity.OauthProvider;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.support.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 초대 보상 지급 이력 1건. uq_invite_rewards_invitee 가 "한 사용자는 평생 1회만 초대 보상 대상"의 DB 방어선.
// 초대자 한도를 넘겨 초대자 몫이 0으로 지급된 경우에도 행은 남긴다 — 중복 사용 판정의 근거이기 때문.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "invite_rewards")
public class InviteReward extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inviter_user_id", nullable = false)
    private User inviter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invitee_user_id", nullable = false)
    private User invitee;

    @Column(name = "inviter_reward_amount", nullable = false)
    private int inviterRewardAmount;

    @Column(name = "invitee_reward_amount", nullable = false)
    private int inviteeRewardAmount;

    // 수령 시점의 oauth 식별자 복사본. 탈퇴는 oauth_accounts 행을 지우므로, 재가입한 새 user_id 를
    // invitee_user_id 로는 막을 수 없다. 이 두 값의 UNIQUE 가 탈퇴 후 재수령의 방어선이다.
    // dev-login 계정은 oauth 행이 없어 null 이며, 그 경우 invitee_user_id UNIQUE 가 대신 막는다.
    @Enumerated(EnumType.STRING)
    @Column(name = "invitee_provider", length = 20)
    private OauthProvider inviteeProvider;

    @Column(name = "invitee_provider_user_id", length = 255)
    private String inviteeProviderUserId;

    public static InviteReward of(User inviter, User invitee, int inviterRewardAmount, int inviteeRewardAmount,
                                  OauthProvider inviteeProvider, String inviteeProviderUserId) {
        InviteReward reward = new InviteReward();
        reward.inviter = inviter;
        reward.invitee = invitee;
        reward.inviterRewardAmount = inviterRewardAmount;
        reward.inviteeRewardAmount = inviteeRewardAmount;
        reward.inviteeProvider = inviteeProvider;
        reward.inviteeProviderUserId = inviteeProviderUserId;
        return reward;
    }
}
