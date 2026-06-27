package com.triples.rougether.domain.shop.repository;

import com.triples.rougether.domain.shop.entity.UserItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserItemRepository extends JpaRepository<UserItem, Long> {

    List<UserItem> findByUserIdAndDeletedAtIsNull(Long userId);
}
