package com.triples.rougether.domain.shop.repository;

import com.triples.rougether.domain.shop.entity.Item;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, Long> {

    List<Item> findByThemeId(Long themeId);

    boolean existsByAssetKey(String assetKey);
}
