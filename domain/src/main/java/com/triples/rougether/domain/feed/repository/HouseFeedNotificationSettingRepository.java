package com.triples.rougether.domain.feed.repository;

import com.triples.rougether.domain.feed.entity.HouseFeedNotificationSetting;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseFeedNotificationSettingRepository extends JpaRepository<HouseFeedNotificationSetting, Long> {
    Optional<HouseFeedNotificationSetting> findByHouseIdAndUserId(Long houseId, Long userId);
    // 새 글 푸시 대상 계산용 — 구성원 수만큼 따로 묻지 않고 한 번에 읽음.
    List<HouseFeedNotificationSetting> findByHouseIdAndUserIdIn(Long houseId, Collection<Long> userIds);
}
