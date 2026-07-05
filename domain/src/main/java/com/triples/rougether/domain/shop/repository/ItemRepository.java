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

    // 활성 아이템 + theme 를 fetch join(N+1 회피).
    @Query("select i from Item i join fetch i.theme where i.active = true")
    List<Item> findActiveWithTheme();

    @Query("select i from Item i join fetch i.theme where i.active = true and i.theme.id = :themeId")
    List<Item> findActiveWithThemeByThemeId(@Param("themeId") Long themeId);
}
