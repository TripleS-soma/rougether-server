package com.triples.rougether.domain.invite.entity;

import com.triples.rougether.domain.support.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 초대 링크 랜딩 클릭 로그(퍼널 분모). 코드 원본에 FK 를 두지 않는 순수 로그 — 무효 코드 클릭도 기록한다.
// valid 는 클릭 시점의 코드 유효 여부(만료·삭제 포함) — 실제 가입/보상 여부는 invite_rewards·house_members 로 본다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "invite_link_clicks")
public class InviteLinkClick extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "link_type", length = 10, nullable = false)
    private InviteLinkType linkType;

    @Column(name = "code", length = 20, nullable = false)
    private String code;

    @Column(name = "valid", nullable = false)
    private boolean valid;

    @Enumerated(EnumType.STRING)
    @Column(name = "os", length = 10, nullable = false)
    private InviteLinkOs os;

    public static InviteLinkClick of(InviteLinkType linkType, String code, boolean valid, InviteLinkOs os) {
        InviteLinkClick click = new InviteLinkClick();
        click.linkType = linkType;
        click.code = code;
        click.valid = valid;
        click.os = os;
        return click;
    }
}
