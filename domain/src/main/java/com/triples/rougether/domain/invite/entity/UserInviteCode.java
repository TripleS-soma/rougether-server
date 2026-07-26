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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 사용자 개인 초대코드 (사용자당 1개, 만료 없음).
// 집 초대코드(house.invite_code)는 집 단위라 초대자를 특정할 수 없어 개인 코드를 따로 둔다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "user_invite_codes")
public class UserInviteCode extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "code", length = 20, nullable = false)
    private String code;

    public static UserInviteCode issue(User user, String code) {
        UserInviteCode inviteCode = new UserInviteCode();
        inviteCode.user = user;
        inviteCode.code = code;
        return inviteCode;
    }
}
