package com.triples.rougether.domain.member.entity;

import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.support.BaseEntity;
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

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "user_wallets")
public class UserWallet extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_type", length = 30, nullable = false)
    private CurrencyType currencyType;

    @Column(name = "balance", nullable = false)
    private int balance;

    // 재화 차감(잔액 검증은 호출 서비스 책임).
    public void deduct(int amount) {
        this.balance -= amount;
    }

    // 재화 적립(뽑기 중복 환급, 보상 지급 등).
    public void add(int amount) {
        this.balance += amount;
    }
}
