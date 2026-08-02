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

    private UserWallet(User user, CurrencyType currencyType, int balance) {
        this.user = user;
        this.currencyType = currencyType;
        this.balance = balance;
    }

    // 잔액 0으로 지갑 발급(가입 이후 지연 발급 등)
    public static UserWallet create(User user, CurrencyType currencyType) {
        return new UserWallet(user, currencyType, 0);
    }

    // 초기 잔액을 지정해 지갑 발급(가입 보너스). 정책값은 SignupWalletPolicy 가 소유함.
    public static UserWallet createWithBalance(User user, CurrencyType currencyType, int initialBalance) {
        return new UserWallet(user, currencyType, initialBalance);
    }

    public void add(int amount) {
        this.balance += amount;
    }

    // 보상 회수(루틴/투두 완료 취소)용 — 정책상 음수 잔액 허용.
    public void subtract(int amount) {
        this.balance -= amount;
    }

    // 소비(구매/뽑기)용 — 잔액 초과 소비 불가. 서비스의 사전 잔액 체크가 뚫리는 동시 요청의 최후 방어선.
    public void spend(int amount) {
        if (this.balance < amount) {
            throw new IllegalStateException(
                    "잔액을 초과해 소비할 수 없습니다: balance=" + balance + ", amount=" + amount);
        }
        this.balance -= amount;
    }
}
