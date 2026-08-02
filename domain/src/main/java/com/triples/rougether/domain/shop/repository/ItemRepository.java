package com.triples.rougether.domain.shop.repository;

import com.triples.rougether.domain.shop.entity.Item;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemRepository extends JpaRepository<Item, Long> {

    List<Item> findByThemeId(Long themeId);

    boolean existsByAssetKey(String assetKey);

    Optional<Item> findByAssetKey(String assetKey);

    // admin 슬롯 편집용: placement_type 조회 + theme fetch join (테마·id 순 정렬).
    @Query("select i from Item i join fetch i.theme where i.placementType = :placementType order by i.theme.id asc, i.id asc")
    List<Item> findByPlacementTypeWithTheme(@Param("placementType") String placementType);

    // 상점 노출용: 활성 테마의 활성 아이템만 + theme fetch join(N+1 회피).
    // spec(domains/shop/features.md) — 테마를 내리면(themes.is_active=false) 그 테마 아이템도 상점에서 숨긴다.
    @Query("select i from Item i join fetch i.theme where i.active = true and i.theme.active = true")
    List<Item> findActiveWithTheme();

    @Query("select i from Item i join fetch i.theme "
            + "where i.active = true and i.theme.active = true and i.theme.id = :themeId")
    List<Item> findActiveWithThemeByThemeId(@Param("themeId") Long themeId);
}
