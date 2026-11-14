package com.triples.rougether.adminapi.user.service;

import com.triples.rougether.adminapi.user.dto.WithdrawalFeedbackSummary;
import com.triples.rougether.domain.member.entity.WithdrawalReason;
import com.triples.rougether.domain.member.repository.WithdrawalFeedbackRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WithdrawalFeedbackQueryService {
    private final WithdrawalFeedbackRepository repository;
    private final Clock clock;

    // 최근 30개 KST 일자에 새로 수집한 응답만 집계함. 과거 누적 탈퇴와 섞지 않음.
    public WithdrawalFeedbackSummary getSummary() {
        LocalDate toDate = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"));
        LocalDate fromDate = toDate.minusDays(29);
        EnumMap<WithdrawalReason, Long> counts = new EnumMap<>(WithdrawalReason.class);
        long skipped = 0;
        for (var row : repository.countReasons(fromDate, toDate)) {
            if (row.getReason() == null) skipped += row.getCount();
            else counts.put(row.getReason(), row.getCount());
        }
        long answered = counts.values().stream().mapToLong(Long::longValue).sum();
        var items = Arrays.stream(WithdrawalReason.values())
                .map(reason -> new WithdrawalFeedbackSummary.Item(reason, counts.getOrDefault(reason, 0L)))
                .toList();
        return new WithdrawalFeedbackSummary(fromDate, toDate, answered + skipped, answered, skipped, items);
    }
}
