package com.triples.rougether.userapi.market.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.market.entity.MarketAsset;
import com.triples.rougether.domain.market.entity.MarketCommand;
import com.triples.rougether.domain.market.repository.MarketAssetRepository;
import com.triples.rougether.domain.market.repository.MarketCommandRepository;
import com.triples.rougether.domain.market.repository.MarketOrderRepository;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.userapi.market.dto.MarketCommandResponse;
import com.triples.rougether.userapi.market.dto.MarketOrderResponse;
import com.triples.rougether.userapi.market.error.MarketErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 주문 접수 결과 조회(#400). 본인 접수만.
@Service
@Transactional(readOnly = true)
public class MarketCommandQueryService {

    private final MarketCommandRepository marketCommandRepository;
    private final MarketOrderRepository marketOrderRepository;
    private final MarketAssetRepository marketAssetRepository;
    private final ItemRepository itemRepository;

    public MarketCommandQueryService(MarketCommandRepository marketCommandRepository,
                                     MarketOrderRepository marketOrderRepository,
                                     MarketAssetRepository marketAssetRepository,
                                     ItemRepository itemRepository) {
        this.marketCommandRepository = marketCommandRepository;
        this.marketOrderRepository = marketOrderRepository;
        this.marketAssetRepository = marketAssetRepository;
        this.itemRepository = itemRepository;
    }

    public MarketCommandResponse get(Long userId, Long commandId) {
        MarketCommand command = marketCommandRepository.findByIdAndUserId(commandId, userId)
                .orElseThrow(() -> new BusinessException(MarketErrorCode.COMMAND_NOT_FOUND));
        MarketOrderResponse order = command.getOrderId() == null ? null
                : marketOrderRepository.findById(command.getOrderId())
                        .map(found -> MarketOrderResponse.of(found, itemOf(found.getAssetId())))
                        .orElse(null);
        return new MarketCommandResponse(command.getId(), command.getStatus(), command.getRejectCode(), order);
    }

    private Item itemOf(Long assetId) {
        MarketAsset asset = marketAssetRepository.findById(assetId).orElseThrow();
        return itemRepository.findById(asset.getItemId()).orElseThrow();
    }
}
