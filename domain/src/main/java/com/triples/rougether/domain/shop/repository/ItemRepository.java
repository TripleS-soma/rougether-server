package com.triples.rougether.domain.shop.repository;

import com.triples.rougether.domain.shop.entity.Item;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemRepository extends JpaRepository<Item, Long> {

    List<Item> findByThemeId(Long themeId);

    boolean existsByAssetKey(String assetKey);

    // 활성 아이템 + theme 를 fetch join(N+1 회피).
    @Query("select i from Item i join fetch i.theme where i.active = true")
    List<Item> findActiveWithTheme();

    @Query("select i from Item i join fetch i.theme where i.active = true and i.theme.id = :themeId")
    List<Item> findActiveWithThemeByThemeId(@Param("themeId") Long themeId);
}
