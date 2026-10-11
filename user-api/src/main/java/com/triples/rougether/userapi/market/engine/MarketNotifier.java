package com.triples.rougether.userapi.market.engine;

import com.triples.rougether.domain.market.entity.MarketOrder;
import com.triples.rougether.domain.market.entity.OrderSide;
import com.triples.rougether.domain.market.repository.MarketAssetRepository;
import com.triples.rougether.domain.market.repository.MarketOrderRepository;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.userapi.notification.message.NotificationContent;
import com.triples.rougether.userapi.notification.message.NotificationMessages;
import com.triples.rougether.userapi.notification.service.NotificationService;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

// 거래소 알림(#432). 엔진 처리 트랜잭션 안에서는 보낼 알림을 이벤트로만 남기고, 커밋된 뒤 알림마다 새 트랜잭션에서
// 저장·발송함. 엔진이 롤백되면 이벤트도 버려져 알림이 없고, 알림 저장이 실패해도 이미 커밋된 체결에 영향이 없음.
// (NotificationService 는 REQUIRED 라 엔진 트랜잭션 안에서 부르면 실패가 체결까지 rollback-only 로 만듦)
// 커밋 직후 서버가 죽으면 그 알림은 유실됨 — push 와 같은 best-effort.
@Component
public class MarketNotifier {

    private static final Logger log = LoggerFactory.getLogger(MarketNotifier.class);
    static final Duration BID_NOTIFICATION_WINDOW = Duration.ofHours(24);

    private final ApplicationEventPublisher events;
    private final MarketOrderRepository orderRepository;
    private final MarketAssetRepository assetRepository;
    private final ItemRepository itemRepository;
    private final NotificationService notificationService;
    private final NotificationRepository notificationRepository;
    private final TransactionTemplate separate;

    public MarketNotifier(ApplicationEventPublisher events, MarketOrderRepository orderRepository,
                          MarketAssetRepository assetRepository, ItemRepository itemRepository,
                          NotificationService notificationService, NotificationRepository notificationRepository,
                          PlatformTransactionManager transactionManager) {
        this.events = events;
        this.orderRepository = orderRepository;
        this.assetRepository = assetRepository;
        this.itemRepository = itemRepository;
        this.notificationService = notificationService;
        this.notificationRepository = notificationRepository;
        this.separate = new TransactionTemplate(transactionManager);
        this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // 호가창에 대기하던 주문(maker)이 체결됨. 체결 건마다 1개, maker 에게만(taker 는 접수 결과 조회로 앎).
    void makerFilled(MarketOrder maker, int price) {
        NotificationType type = maker.getSide() == OrderSide.SELL
                ? NotificationType.MARKET_SELL_FILLED : NotificationType.MARKET_BUY_FILLED;
        events.publishEvent(new Requested(maker.getUserId(), type, maker.getAssetId(), price, maker.getId()));
    }

    // 구매 주문이 호가창에 대기로 남음 → 그 종목 판매 대기자 전원에게. 판매자·종목당 24시간 1회(onRequested 에서 거름).
    void bidPlaced(MarketOrder bid) {
        for (Long sellerId : orderRepository.findOpenSellerIds(bid.getAssetId())) {
            if (!sellerId.equals(bid.getUserId())) {
                events.publishEvent(new Requested(sellerId, NotificationType.MARKET_BID_PLACED, bid.getAssetId(),
                        bid.getPrice(), bid.getAssetId()));
            }
        }
    }

    // AFTER_COMMIT 리스너의 예외는 커밋한 쪽(엔진)으로 되던져지므로 여기서 전부 삼킴.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(Requested requested) {
        try {
            separate.executeWithoutResult(status -> {
                if (isThrottled(requested)) {
                    return;
                }
                notificationService.send(requested.userId(), content(requested), requested.refId());
            });
        } catch (RuntimeException e) {
            log.warn("market notification failed - userId={}, type={}, refId={}",
                    requested.userId(), requested.type(), requested.refId(), e);
        }
    }

    // 구매 대기 등록은 싸게 걸고 취소하기를 반복하면 판매자 전원에게 계속 푸시가 가므로 판매자·종목당 24시간 1회.
    // created_at 이 시스템 시각(@CreatedDate)으로 채워지므로 같은 기준으로 비교함. 엔진 스레드 하나에서
    // 순서대로 실행되는 리스너라 확인과 저장 사이 경합이 없음.
    private boolean isThrottled(Requested requested) {
        return requested.type() == NotificationType.MARKET_BID_PLACED
                && notificationRepository.existsByUserAndTypeAndRefIdSince(requested.userId(), requested.type(),
                requested.refId(), Instant.now().minus(BID_NOTIFICATION_WINDOW));
    }

    private NotificationContent content(Requested requested) {
        Item item = assetRepository.findById(requested.assetId())
                .flatMap(asset -> itemRepository.findById(asset.getItemId()))
                .orElseThrow();
        String name = item.getName();
        String englishName = item.nameIn("en");
        return switch (requested.type()) {
            case MARKET_SELL_FILLED -> NotificationMessages.marketSellFilled(name, englishName, requested.price());
            case MARKET_BUY_FILLED -> NotificationMessages.marketBuyFilled(name, englishName, requested.price());
            case MARKET_BID_PLACED -> NotificationMessages.marketBidPlaced(name, englishName, requested.price());
            default -> throw new IllegalArgumentException("not a market notification: " + requested.type());
        };
    }

    public record Requested(Long userId, NotificationType type, Long assetId, int price, Long refId) {
    }
}
