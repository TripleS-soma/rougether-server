package com.triples.rougether.domain.house.repository;

import com.triples.rougether.domain.house.entity.HouseMemberCheer;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseMemberCheerRepository extends JpaRepository<HouseMemberCheer, Long> {

    // 하루 1회(타입별) 조기 거부용. 동시 요청은 UNIQUE(sender,target,type,date) 가 최후 방어선.
    boolean existsBySender_IdAndTarget_IdAndCheerTypeAndCheerDate(
            Long senderUserId, Long targetUserId, String cheerType, LocalDate cheerDate);
}
