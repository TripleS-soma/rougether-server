package com.triples.rougether.userapi.market.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.market.entity.MarketAsset;
import com.triples.rougether.domain.market.entity.MarketAssetStatus;
import com.triples.rougether.domain.market.entity.MarketOrder;
import com.triples.rougether.domain.market.entity.MarketTrade;
import com.triples.rougether.domain.market.repository.MarketAskSummary;
import com.triples.rougether.domain.market.repository.MarketAssetRepository;
import com.triples.rougether.domain.market.repository.MarketOrderRepository;
import com.triples.rougether.domain.market.repository.MarketPriceLevel;
import com.triples.rougether.domain.market.repository.MarketTradeRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.market.dto.MarketAssetListResponse;
import com.triples.rougether.userapi.market.dto.MarketAssetListResponse.AssetCard;
import com.triples.rougether.userapi.market.dto.MarketAssetResponse;
import com.triples.rougether.userapi.market.dto.MarketAssetResponse.PriceLevel;
import com.triples.rougether.userapi.market.dto.MarketOrderResponse;
import com.triples.rougether.userapi.market.dto.MarketTradeListResponse;
import com.triples.rougether.userapi.market.dto.MarketTradeListResponse.TradeItem;
import com.triples.rougether.userapi.market.dto.MyMarketOrderListResponse;
import com.triples.rougether.userapi.market.dto.MyMarketOrderStatus;
import com.triples.rougether.userapi.market.error.MarketErrorCode;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 거래소 조회(#404). 호가·목록 집계는 DB 기준(엔진 메모리를 읽지 않음). 목록은 한 페이지 분량을 모아 한 번에 조회함.
@Service
@Transactional(readOnly = true)
public class MarketQueryService {

    static final int DEPTH = 10;

    private final MarketAssetRepository assetRepository;
    private final MarketOrderRepository orderRepository;
    private final MarketTradeRepository tradeRepository;
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;
    private final UserItemRepository userItemRepository;

    public MarketQueryService(MarketAssetRepository assetRepository, MarketOrderRepository orderRepository,
                              MarketTradeRepository tradeRepository, ItemRepository itemRepository,
                              UserRepository userRepository, UserItemRepository userItemRepository) {
        this.assetRepository = assetRepository;
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        this.itemRepository = itemRepository;
        this.userRepository = userRepository;
        this.userItemRepository = userItemRepository;
    }

    // 요청자가 차단한 제작자의 종목은 목록·totalElements 에서 빠짐(#399). 상세는 차단과 무관.
    public MarketAssetListResponse listAssets(Long viewerId, int page, int size) {
        Page<MarketAsset> assets = assetRepository.findListedFor(viewerId, MarketAssetStatus.ACTIVE,
                PageRequest.of(page, size));
        List<Long> assetIds = assets.getContent().stream().map(MarketAsset::getId).toList();
        Map<Long, Item> items = itemsById(assets.getContent().stream().map(MarketAsset::getItemId).toList());
        Map<Long, String> nicknames = nicknamesById(assets.getContent().stream().map(MarketAsset::getCreatorUserId).toList());
        Map<Long, MarketAskSummary> asks = assetIds.isEmpty() ? Map.of()
                : orderRepository.summarizeAsks(assetIds).stream()
                        .collect(Collectors.toMap(MarketAskSummary::getAssetId, Function.identity()));
        Map<Long, Integer> lastPrices = assetIds.isEmpty() ? Map.of()
                : tradeRepository.findLatestByAssetIds(assetIds).stream()
                        .collect(Collectors.toMap(MarketTrade::getAssetId, MarketTrade::getPrice));

        List<AssetCard> cards = assets.getContent().stream().map(asset -> {
            Item item = items.get(asset.getItemId());
            MarketAskSummary ask = asks.get(asset.getId());
            return new AssetCard(asset.getId(), asset.getItemId(), item.getName(), item.getAssetKey(),
                    nicknames.get(asset.getCreatorUserId()), asset.getTotalSupply(),
                    ask == null ? null : ask.getBestAskPrice(), ask == null ? 0 : ask.getAskQuantity().intValue(),
                    lastPrices.get(asset.getId()), asset.getStatus());
        }).toList();
        return new MarketAssetListResponse(cards, page, size, assets.getTotalElements());
    }

    public MarketAssetResponse getAsset(Long userId, Long assetId) {
        MarketAsset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new BusinessException(MarketErrorCode.ASSET_NOT_FOUND));
        Item item = itemRepository.findById(asset.getItemId()).orElseThrow();
        Integer lastPrice = tradeRepository.findFirstByAssetIdOrderByIdDesc(assetId).map(MarketTrade::getPrice).orElse(null);
        List<PriceLevel> asks = levels(orderRepository.findAskLevels(assetId, PageRequest.of(0, DEPTH)));
        List<PriceLevel> bids = levels(orderRepository.findBidLevels(assetId, PageRequest.of(0, DEPTH)));
        return new MarketAssetResponse(asset.getId(), asset.getItemId(), item.getName(), item.getAssetKey(),
                nicknameOf(asset.getCreatorUserId()),
                asset.isCreator(userId), asset.getTotalSupply(), asset.getUnissuedQuantity(), asset.getStatus(),
                lastPrice, userItemRepository.existsByUserIdAndItemIdAndDeletedAtIsNull(userId, asset.getItemId()),
                asks, bids);
    }

    public MarketTradeListResponse trades(Long assetId, int page, int size) {
        if (!assetRepository.existsById(assetId)) {
            throw new BusinessException(MarketErrorCode.ASSET_NOT_FOUND);
        }
        Page<MarketTrade> trades = tradeRepository.findByAssetIdOrderByIdDesc(assetId, PageRequest.of(page, size));
        List<TradeItem> items = trades.getContent().stream()
                .map(t -> new TradeItem(t.getId(), t.getPrice(), t.getQuantity(), t.getCreatedAt())).toList();
        return new MarketTradeListResponse(items, page, size, trades.getTotalElements());
    }

    public MyMarketOrderListResponse myOrders(Long userId, MyMarketOrderStatus status, int page, int size) {
        Page<MarketOrder> orders = orderRepository.findByUserIdAndStatusInOrderByIdDesc(userId, status.statuses(),
                PageRequest.of(page, size));
        Map<Long, MarketAsset> assets = assetRepository.findAllById(
                        orders.getContent().stream().map(MarketOrder::getAssetId).distinct().toList())
                .stream().collect(Collectors.toMap(MarketAsset::getId, Function.identity()));
        Map<Long, Item> items = itemsById(assets.values().stream().map(MarketAsset::getItemId).toList());
        List<MarketOrderResponse> responses = orders.getContent().stream()
                .map(order -> MarketOrderResponse.of(order, items.get(assets.get(order.getAssetId()).getItemId())))
                .toList();
        return new MyMarketOrderListResponse(responses, page, size, orders.getTotalElements());
    }

    private Map<Long, Item> itemsById(Collection<Long> itemIds) {
        return itemRepository.findAllById(itemIds.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(Item::getId, Function.identity()));
    }

    // 제작자 ID 가 비었거나(탈퇴 정리) 탈퇴한 사용자면 닉네임을 내려주지 않음
    private Map<Long, String> nicknamesById(Collection<Long> userIds) {
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        return userRepository.findAllById(ids).stream()
                .filter(user -> !user.isDeleted() && user.getNickname() != null)
                .collect(Collectors.toMap(User::getId, User::getNickname));
    }

    private String nicknameOf(Long userId) {
        return userId == null ? null : nicknamesById(List.of(userId)).get(userId);
    }

    private static List<PriceLevel> levels(List<MarketPriceLevel> rows) {
        return rows.stream().map(row -> new PriceLevel(row.getPrice(), row.getQuantity().intValue())).toList();
    }
}
