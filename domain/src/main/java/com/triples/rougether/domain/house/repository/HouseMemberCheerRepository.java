package com.triples.rougether.domain.house.repository;

import com.triples.rougether.domain.house.entity.HouseMemberCheer;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseMemberCheerRepository extends JpaRepository<HouseMemberCheer, Long> {

    // 하루 5회(타입별) 한도 조기 거부 + 다음 daily_seq 계산용. 동시 요청은 UNIQUE(sender,target,type,date,daily_seq) 가 최후 방어선.
    int countBySender_IdAndTarget_IdAndCheerTypeAndCheerDate(
            Long senderUserId, Long targetUserId, String cheerType, LocalDate cheerDate);

    // 동거 봇 응원(#310): 봇이 오늘 보낸 응원 전체 — 사람별 횟수(2회/일)·마지막 응원 시각 판정용.
    List<HouseMemberCheer> findBySender_IdAndCheerDate(Long senderId, LocalDate cheerDate);
}
