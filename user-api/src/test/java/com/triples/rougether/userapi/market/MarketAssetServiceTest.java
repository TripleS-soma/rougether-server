package com.triples.rougether.userapi.market;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.furniture.repository.FurnitureGenerationJobRepository;
import com.triples.rougether.domain.market.repository.MarketAssetRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.market.error.MarketErrorCode;
import com.triples.rougether.userapi.market.service.MarketAssetService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class MarketAssetServiceTest {

    @Test
    void 사전_확인을_통과해도_동시_발행으로_유니크가_깨지면_이미_상장_오류로_바꾼다() {
        UserItemRepository userItems = mock(UserItemRepository.class);
        FurnitureGenerationJobRepository jobs = mock(FurnitureGenerationJobRepository.class);
        MarketAssetRepository assets = mock(MarketAssetRepository.class);
        MarketAssetService service = new MarketAssetService(userItems, jobs, assets, mock(UserRepository.class),
                Clock.fixed(Instant.parse("2026-09-28T00:00:00Z"), ZoneOffset.UTC));
        Theme theme = mock(Theme.class);
        when(theme.getCode()).thenReturn("photo_furniture");
        Item item = mock(Item.class);
        when(item.getId()).thenReturn(320L);
        when(item.getTheme()).thenReturn(theme);
        UserItem owned = mock(UserItem.class);
        when(owned.getItem()).thenReturn(item);
        when(userItems.findOwnedWithItem(7L, 77L)).thenReturn(Optional.of(owned));
        when(jobs.findCreatorUserIdByItemId(320L)).thenReturn(Optional.of(7L));
        when(assets.existsByItemId(320L)).thenReturn(false);
        when(assets.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_market_assets_item"));

        assertThatThrownBy(() -> service.issue(7L, 77L, 3))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MarketErrorCode.ASSET_ALREADY_LISTED));
    }
}
