package com.triples.rougether.domain.shop.repository;

import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.PlacementType;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemRepository extends JpaRepository<Item, Long> {

    List<Item> findByThemeId(Long themeId);

    @EntityGraph(attributePaths = "theme")
    List<Item> findByActiveTrueAndThemeActiveTrueOrderByIdAsc();

    @EntityGraph(attributePaths = "theme")
    List<Item> findAllByOrderByIdAsc();

    @Query("""
            select item
            from Item item
            join fetch item.theme theme
            where item.active = true
              and theme.active = true
              and (:themeId is null or theme.id = :themeId)
              and (:placementType is null or item.placementType = :placementType)
            order by item.id asc
            """)
    List<Item> findCatalogItems(
            @Param("themeId") Long themeId,
            @Param("placementType") PlacementType placementType);
}
