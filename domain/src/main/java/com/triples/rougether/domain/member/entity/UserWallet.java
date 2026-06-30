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

    private UserWallet(User user, CurrencyType currencyType) {
        this.user = user;
        this.currencyType = currencyType;
        this.balance = 0;
    }

    // 가입 시 잔액 0으로 지갑 발급
    public static UserWallet create(User user, CurrencyType currencyType) {
        return new UserWallet(user, currencyType);
    }

    public void add(int amount) {
        this.balance += amount;
    }

    public void subtract(int amount) {
        this.balance -= amount;
    }
}
