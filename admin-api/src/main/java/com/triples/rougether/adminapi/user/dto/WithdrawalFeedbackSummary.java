package com.triples.rougether.adminapi.user.dto;

import com.triples.rougether.domain.member.entity.WithdrawalReason;
import java.time.LocalDate;
import java.util.List;

public record WithdrawalFeedbackSummary(LocalDate fromDate, LocalDate toDate,
                                        long recordedWithdrawals, long answeredWithdrawals,
                                        long skippedWithdrawals, List<Item> items) {
    public record Item(WithdrawalReason reason, long count) {
    }
}
