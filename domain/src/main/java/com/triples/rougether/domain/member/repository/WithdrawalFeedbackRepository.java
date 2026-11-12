package com.triples.rougether.domain.member.repository;

import com.triples.rougether.domain.member.entity.WithdrawalFeedback;
import com.triples.rougether.domain.member.entity.WithdrawalReason;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WithdrawalFeedbackRepository extends JpaRepository<WithdrawalFeedback, Long> {
    @Query("""
            select f.reason as reason, count(f) as count from WithdrawalFeedback f
            where f.responseDate between :fromDate and :toDate group by f.reason
            """)
    List<ReasonCount> countReasons(@Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);

    interface ReasonCount {
        WithdrawalReason getReason();
        long getCount();
    }
}
