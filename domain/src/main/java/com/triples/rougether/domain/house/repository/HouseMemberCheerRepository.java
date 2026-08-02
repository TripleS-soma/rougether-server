package com.triples.rougether.domain.house.repository;

import com.triples.rougether.domain.house.entity.HouseMemberCheer;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseMemberCheerRepository extends JpaRepository<HouseMemberCheer, Long> {

    // 하루 5회(타입별) 한도 조기 거부 + 다음 daily_seq 계산용. 동시 요청은 UNIQUE(sender,target,type,date,daily_seq) 가 최후 방어선.
    int countBySender_IdAndTarget_IdAndCheerTypeAndCheerDate(
            Long senderUserId, Long targetUserId, String cheerType, LocalDate cheerDate);
}
