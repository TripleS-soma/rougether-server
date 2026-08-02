package com.triples.rougether.domain.invite.entity;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.support.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    public static InviteReward of(User inviter, User invitee, int inviterRewardAmount, int inviteeRewardAmount) {
        InviteReward reward = new InviteReward();
        reward.inviter = inviter;
        reward.invitee = invitee;
        reward.inviterRewardAmount = inviterRewardAmount;
        reward.inviteeRewardAmount = inviteeRewardAmount;
        return reward;
    }
}
