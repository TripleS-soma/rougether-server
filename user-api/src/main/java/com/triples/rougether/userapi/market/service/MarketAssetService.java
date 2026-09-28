package com.triples.rougether.userapi.market.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.furniture.repository.FurnitureGenerationJobRepository;
import com.triples.rougether.domain.market.entity.MarketAsset;
import com.triples.rougether.domain.market.repository.MarketAssetRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.market.dto.MarketAssetResponse;
import com.triples.rougether.userapi.global.persistence.UniqueViolations;
import com.triples.rougether.userapi.market.error.MarketErrorCode;
import java.time.Clock;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 거래소 종목 발행(#406). 사진으로 만든 AI 가구를 만든 사람만, 가구당 1회 발행함.
@Service
public class MarketAssetService {

    static final String TRADABLE_THEME_CODE = "photo_furniture";

    private final UserItemRepository userItemRepository;
    private final FurnitureGenerationJobRepository furnitureGenerationJobRepository;
    private final MarketAssetRepository marketAssetRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public MarketAssetService(UserItemRepository userItemRepository,
                              FurnitureGenerationJobRepository furnitureGenerationJobRepository,
                              MarketAssetRepository marketAssetRepository,
                              UserRepository userRepository,
                              Clock clock) {
        this.userItemRepository = userItemRepository;
        this.furnitureGenerationJobRepository = furnitureGenerationJobRepository;
        this.marketAssetRepository = marketAssetRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional
    public MarketAssetResponse issue(Long userId, Long userItemId, int totalSupply) {
        UserItem owned = userItemRepository.findOwnedWithItem(userId, userItemId)
                .orElseThrow(() -> new BusinessException(MarketErrorCode.ITEM_NOT_OWNED));
        Item item = owned.getItem();
        if (!TRADABLE_THEME_CODE.equals(item.getTheme().getCode())) {
            throw new BusinessException(MarketErrorCode.ITEM_NOT_TRADABLE);
        }
        Long creatorId = furnitureGenerationJobRepository.findCreatorUserIdByItemId(item.getId())
                .orElseThrow(() -> new BusinessException(MarketErrorCode.NOT_CREATOR));
        if (!creatorId.equals(userId)) {
            throw new BusinessException(MarketErrorCode.NOT_CREATOR);
        }
        if (marketAssetRepository.existsByItemId(item.getId())) {
            throw new BusinessException(MarketErrorCode.ASSET_ALREADY_LISTED);
        }

        MarketAsset asset;
        try {
            // 동시 발행 경합은 uk_market_assets_item 이 최종 방어선
            asset = marketAssetRepository.saveAndFlush(
                    MarketAsset.issue(item.getId(), userId, totalSupply, clock.instant()));
        } catch (DataIntegrityViolationException race) {
            if (UniqueViolations.isViolationOf(race, "uk_market_assets_item")) {
                throw new BusinessException(MarketErrorCode.ASSET_ALREADY_LISTED);
            }
            throw race;
        }

        User creator = userRepository.getReferenceById(userId);
        // 방금 발행한 종목이라 호가·체결 이력이 없음
        return new MarketAssetResponse(asset.getId(), item.getId(), item.getName(), item.getAssetKey(),
                creator.getNickname(), true, asset.getTotalSupply(), asset.getUnissuedQuantity(),
                asset.getStatus(), null, true, List.of(), List.of());
    }
}
