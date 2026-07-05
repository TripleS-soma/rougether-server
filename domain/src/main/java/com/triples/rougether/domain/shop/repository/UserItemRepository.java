package com.triples.rougether.domain.shop.repository;

import com.triples.rougether.domain.shop.entity.UserItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserItemRepository extends JpaRepository<UserItem, Long> {

    List<UserItem> findByUserIdAndDeletedAtIsNull(Long userId);

    // 인벤토리 조회: item+theme fetch join(N+1 회피), 최근 획득 먼저. categoryCode 는 선택 필터.
    @Query("select ui from UserItem ui join fetch ui.item i join fetch i.theme "
            + "where ui.user.id = :userId and ui.deletedAt is null "
            + "and (:categoryCode is null or i.categoryCode = :categoryCode) "
            + "order by ui.acquiredAt desc, ui.id desc")
    List<UserItem> findInventoryByUserId(@Param("userId") Long userId,
                                         @Param("categoryCode") String categoryCode);

    boolean existsByUserIdAndItemIdAndDeletedAtIsNull(Long userId, Long itemId);
}
