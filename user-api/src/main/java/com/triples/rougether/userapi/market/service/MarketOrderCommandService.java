package com.triples.rougether.userapi.market.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.market.entity.MarketAsset;
import com.triples.rougether.domain.market.entity.MarketCommand;
import com.triples.rougether.domain.market.entity.MarketOrder;
import com.triples.rougether.domain.market.entity.OrderSide;
import com.triples.rougether.domain.market.entity.OrderSource;
import com.triples.rougether.domain.market.entity.OrderStatus;
import com.triples.rougether.domain.market.repository.MarketAssetRepository;
import com.triples.rougether.domain.market.repository.MarketCommandRepository;
import com.triples.rougether.domain.market.repository.MarketOrderRepository;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.entity.WalletHistory;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.room.repository.PersonalRoomRepository;
import com.triples.rougether.domain.room.repository.RoomItemPlacementRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shared.WalletHistoryReason;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.global.persistence.UniqueViolations;
import com.triples.rougether.userapi.market.dto.MarketCommandAcceptedResponse;
import com.triples.rougether.userapi.market.dto.PlaceOrderRequest;
import com.triples.rougether.userapi.market.error.MarketErrorCode;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import java.time.Clock;
import java.time.Instant;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 거래소 주문 접수·취소(#400). 대가를 먼저 맡기고(에스크로) 접수 대장에 넣은 뒤 202 로 돌려줌. 체결은 엔진이 비동기로 처리.
// 락 순서: user → (wallet | market_assets). 사용자 행 잠금을 트랜잭션 첫 문장으로 둬서 같은 사용자의 동시 접수를 직렬화하고,
// 이후 조회가 앞선 접수의 커밋 결과를 보게 함(1인 1개 판정·requestId 재요청 판정).
@Service
public class MarketOrderCommandService {

    private static final String REQUEST_UNIQUE = "uk_market_commands_request";

    private final UserRepository userRepository;
    private final UserWalletRepository userWalletRepository;
    private final UserItemRepository userItemRepository;
    private final RoomItemPlacementRepository roomItemPlacementRepository;
    private final PersonalRoomRepository personalRoomRepository;
    private final MarketAssetRepository marketAssetRepository;
    private final MarketCommandRepository marketCommandRepository;
    private final MarketOrderRepository marketOrderRepository;
    private final WalletHistoryRecorder walletHistoryRecorder;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public MarketOrderCommandService(UserRepository userRepository,
                                     UserWalletRepository userWalletRepository,
                                     UserItemRepository userItemRepository,
                                     RoomItemPlacementRepository roomItemPlacementRepository,
                                     PersonalRoomRepository personalRoomRepository,
                                     MarketAssetRepository marketAssetRepository,
                                     MarketCommandRepository marketCommandRepository,
                                     MarketOrderRepository marketOrderRepository,
                                     WalletHistoryRecorder walletHistoryRecorder,
                                     Clock clock,
                                     PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.userWalletRepository = userWalletRepository;
        this.userItemRepository = userItemRepository;
        this.roomItemPlacementRepository = roomItemPlacementRepository;
        this.personalRoomRepository = personalRoomRepository;
        this.marketAssetRepository = marketAssetRepository;
        this.marketCommandRepository = marketCommandRepository;
        this.marketOrderRepository = marketOrderRepository;
        this.walletHistoryRecorder = walletHistoryRecorder;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public MarketCommandAcceptedResponse place(Long userId, PlaceOrderRequest request) {
        Predicate<MarketCommand> same = existing -> existing.isSamePlace(request.assetId(), request.side(),
                request.source(), request.price(), request.quantity());
        return onceByRequestId(userId, request.requestId(), same, () -> placeInTransaction(userId, request, same));
    }

    public MarketCommandAcceptedResponse cancel(Long userId, Long orderId, String requestId) {
        Predicate<MarketCommand> same = existing -> existing.isSameCancel(orderId);
        return onceByRequestId(userId, requestId, same, () -> cancelInTransaction(userId, orderId, requestId, same));
    }

    // 같은 requestId 동시 요청이 사전 조회를 모두 통과하면 접수 INSERT(마지막 문장)의 유니크 위반으로 하나만 남음.
    // 위반 쪽은 에스크로까지 전부 롤백되고 먼저 들어간 접수를 돌려받음.
    private MarketCommandAcceptedResponse onceByRequestId(Long userId, String requestId, Predicate<MarketCommand> same,
                                                          Supplier<MarketCommandAcceptedResponse> work) {
        try {
            return transaction.execute(status -> work.get());
        } catch (DataIntegrityViolationException race) {
            if (!UniqueViolations.isViolationOf(race, REQUEST_UNIQUE)) {
                throw race;
            }
            return transaction.execute(status -> marketCommandRepository.findByUserIdAndRequestId(userId, requestId)
                    .map(existing -> replay(existing, same))
                    .orElseThrow(() -> race));
        }
    }

    private MarketCommandAcceptedResponse placeInTransaction(Long userId, PlaceOrderRequest request,
                                                             Predicate<MarketCommand> same) {
        lockUser(userId);
        var existing = marketCommandRepository.findByUserIdAndRequestId(userId, request.requestId());
        if (existing.isPresent()) {
            return replay(existing.get(), same);
        }
        // 발행 재고 매도는 처음부터 잠금 조회함. 일반 조회로 먼저 올리면 뒤의 잠금 조회가 영속성 컨텍스트의
        // 예전 인스턴스를 돌려줘 엔진·운영자의 재고 환급이나 정지를 덮어쓸 수 있음.
        boolean issuance = request.side() == OrderSide.SELL && request.source() == OrderSource.ISSUANCE;
        MarketAsset asset = (issuance
                ? marketAssetRepository.findWithLockById(request.assetId())
                : marketAssetRepository.findById(request.assetId()))
                .orElseThrow(() -> new BusinessException(MarketErrorCode.ASSET_NOT_FOUND));
        if (asset.isSuspended()) {
            throw new BusinessException(MarketErrorCode.ASSET_SUSPENDED);
        }
        Instant now = clock.instant();
        if (request.side() == OrderSide.BUY) {
            return placeBuy(userId, request, asset, now);
        }
        return request.source() == OrderSource.INVENTORY
                ? placeInventorySell(userId, request, asset, now)
                : placeIssuanceSell(userId, request, asset, now);
    }

    private MarketCommandAcceptedResponse placeBuy(Long userId, PlaceOrderRequest request, MarketAsset asset, Instant now) {
        requireNotHolding(userId, asset);
        UserWallet wallet = userWalletRepository.findWithLockByUserIdAndCurrencyType(userId, CurrencyType.COIN)
                .orElseThrow(() -> new BusinessException(MarketErrorCode.INSUFFICIENT_COIN));
        int amount = request.price() * request.quantity();
        if (wallet.getBalance() < amount) {
            throw new BusinessException(MarketErrorCode.INSUFFICIENT_COIN);
        }
        wallet.spend(amount);
        MarketCommand command = saveCommand(MarketCommand.place(userId, request.requestId(), asset.getId(),
                OrderSide.BUY, null, request.price(), request.quantity(), amount, null, now));
        walletHistoryRecorder.record(wallet, -amount, WalletHistoryReason.MARKET_ORDER_ESCROW,
                WalletHistory.SOURCE_MARKET_COMMAND, command.getId());
        return MarketCommandAcceptedResponse.from(command);
    }

    private MarketCommandAcceptedResponse placeInventorySell(Long userId, PlaceOrderRequest request, MarketAsset asset,
                                                             Instant now) {
        UserItem owned = userItemRepository.findByUserIdAndItemIdAndDeletedAtIsNull(userId, asset.getItemId())
                .orElseThrow(() -> new BusinessException(MarketErrorCode.ITEM_NOT_OWNED));
        // 배치 조회가 deleted_at 을 거르지 않으므로 맡기기 전에 방에서 먼저 뺌.
        // 방 배치 저장(RoomCommandService.updateLayout)도 방 행을 잠그므로 직렬화되어 맡긴 가구가 다시 배치되지 않음.
        // 락 순서 user → room(방 행을 먼저 잡고 user 를 잡는 경로 없음).
        personalRoomRepository.findWithLockById(userId);
        roomItemPlacementRepository.deleteByUserItemId(owned.getId());
        owned.deactivate(now);
        MarketCommand command = saveCommand(MarketCommand.place(userId, request.requestId(), asset.getId(),
                OrderSide.SELL, OrderSource.INVENTORY, request.price(), 1, 1, owned.getId(), now));
        return MarketCommandAcceptedResponse.from(command);
    }

    // asset 은 호출 측이 이미 잠금 조회한 인스턴스
    private MarketCommandAcceptedResponse placeIssuanceSell(Long userId, PlaceOrderRequest request, MarketAsset asset,
                                                            Instant now) {
        if (!asset.isCreator(userId)) {
            throw new BusinessException(MarketErrorCode.NOT_CREATOR);
        }
        if (!asset.takeUnissued(request.quantity(), now)) {
            throw new BusinessException(MarketErrorCode.INSUFFICIENT_SUPPLY);
        }
        MarketCommand command = saveCommand(MarketCommand.place(userId, request.requestId(), asset.getId(),
                OrderSide.SELL, OrderSource.ISSUANCE, request.price(), request.quantity(), request.quantity(), null, now));
        return MarketCommandAcceptedResponse.from(command);
    }

    private MarketCommandAcceptedResponse cancelInTransaction(Long userId, Long orderId, String requestId,
                                                              Predicate<MarketCommand> same) {
        lockUser(userId);
        var existing = marketCommandRepository.findByUserIdAndRequestId(userId, requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), same);
        }
        MarketOrder order = marketOrderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new BusinessException(MarketErrorCode.ORDER_NOT_FOUND));
        if (order.getStatus() != OrderStatus.OPEN) {
            throw new BusinessException(MarketErrorCode.ORDER_NOT_OPEN);
        }
        MarketCommand command = saveCommand(MarketCommand.cancel(userId, requestId, order.getAssetId(), orderId,
                clock.instant()));
        return MarketCommandAcceptedResponse.from(command);
    }

    // 1인 1개: 활성 보유, 미체결 매수·보유분 매도, 엔진 처리 전 접수를 모두 보유로 셈.
    private void requireNotHolding(Long userId, MarketAsset asset) {
        if (userItemRepository.existsByUserIdAndItemIdAndDeletedAtIsNull(userId, asset.getItemId())
                || marketOrderRepository.existsOpenHolding(userId, asset.getId())
                || marketCommandRepository.existsPendingHolding(userId, asset.getId())) {
            throw new BusinessException(MarketErrorCode.ALREADY_HOLDING);
        }
    }

    // 같은 requestId 재요청은 같은 내용일 때만 기존 접수를 돌려줌. 다른 내용이면 앱 버그로 보고 거절.
    private MarketCommandAcceptedResponse replay(MarketCommand existing, Predicate<MarketCommand> same) {
        if (!same.test(existing)) {
            throw new BusinessException(MarketErrorCode.REQUEST_CONFLICT);
        }
        return MarketCommandAcceptedResponse.from(existing);
    }

    private void lockUser(Long userId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_FOUND));
    }

    // 에스크로 뒤 마지막으로 접수를 넣음. flush 로 requestId 유니크 위반을 이 자리에서 드러냄.
    private MarketCommand saveCommand(MarketCommand command) {
        return marketCommandRepository.saveAndFlush(command);
    }
}
