package com.triples.rougether.domain.shop.repository;

import com.triples.rougether.domain.shop.entity.UserItem;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

public interface UserItemRepository extends JpaRepository<UserItem, Long> {

    List<UserItem> findByUserIdAndDeletedAtIsNull(Long userId);

    @Query("""
            select ui.item.id
            from UserItem ui
            where ui.user.id = :userId
              and ui.deletedAt is null
            """)
    List<Long> findActiveItemIdsByUserId(@Param("userId") Long userId);
}
