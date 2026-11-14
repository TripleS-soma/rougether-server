package com.triples.rougether.domain.member.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 계정 식별자와 자유 입력 없이 선택 사유와 KST 집계일만 남김. 미선택도 null로 집계함.
@Getter
@Entity
@Table(name = "withdrawal_feedback")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WithdrawalFeedback {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 30)
    private WithdrawalReason reason;

    @Column(name = "response_date", nullable = false)
    private LocalDate responseDate;

    public static WithdrawalFeedback record(WithdrawalReason reason, LocalDate responseDate) {
        WithdrawalFeedback feedback = new WithdrawalFeedback();
        feedback.reason = reason;
        feedback.responseDate = responseDate;
        return feedback;
    }
}
