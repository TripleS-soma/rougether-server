package com.triples.rougether.userapi.market.engine;

import com.triples.rougether.domain.market.entity.OrderSide;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

// 종목별 호가창(순수 자바, 스레드 안전하지 않음 — 엔진 스레드 하나만 씀).
// 가격 우선, 같은 가격은 먼저 들어온(engine_seq 작은) 주문 우선. 체결가는 먼저 걸려 있던 주문(maker)의 가격.
// DB 의 OPEN 주문이 정본이고 이 호가창은 캐시라, 처리 트랜잭션이 실패하면 엔진이 DB 에서 다시 적재함.
public final class OrderBook {

    public record Fill(long makerOrderId, int price, int quantity) {
    }

    private static final class Resting {
        private final long orderId;
        private final long userId;
        private int remaining;

        private Resting(long orderId, long userId, int remaining) {
            this.orderId = orderId;
            this.userId = userId;
            this.remaining = remaining;
        }
    }

    private static final class AssetBook {
        // 매수: 비싼 가격부터, 매도: 싼 가격부터. 각 가격 안에서는 들어온 순서(FIFO)
        private final NavigableMap<Integer, ArrayDeque<Resting>> bids = new TreeMap<>(Comparator.reverseOrder());
        private final NavigableMap<Integer, ArrayDeque<Resting>> asks = new TreeMap<>();

        private NavigableMap<Integer, ArrayDeque<Resting>> side(OrderSide side) {
            return side == OrderSide.BUY ? bids : asks;
        }
    }

    private final Map<Long, AssetBook> books = new HashMap<>();

    public void clear() {
        books.clear();
    }

    // DB 적재용. engine_seq 오름차순으로 넣어야 가격 안의 순서가 맞음.
    public void rest(long assetId, long orderId, long userId, OrderSide side, int price, int remaining) {
        book(assetId).side(side).computeIfAbsent(price, p -> new ArrayDeque<>())
                .addLast(new Resting(orderId, userId, remaining));
    }

    // 들어온 주문이 체결될 수 있는 반대편 주문 중 같은 사용자의 주문이 있는지(자기 주문끼리 체결 방지)
    public boolean crossesOwn(long assetId, long userId, OrderSide side, int price) {
        AssetBook book = books.get(assetId);
        if (book == null) {
            return false;
        }
        for (Map.Entry<Integer, ArrayDeque<Resting>> level : book.side(opposite(side)).entrySet()) {
            if (!crosses(side, price, level.getKey())) {
                break;
            }
            for (Resting resting : level.getValue()) {
                if (resting.userId == userId) {
                    return true;
                }
            }
        }
        return false;
    }

    // 들어온 주문을 반대편과 맞추고 체결 목록을 돌려줌. 남은 수량은 호가창에 올림.
    public List<Fill> match(long assetId, long orderId, long userId, OrderSide side, int price, int quantity) {
        AssetBook book = book(assetId);
        NavigableMap<Integer, ArrayDeque<Resting>> opposite = book.side(opposite(side));
        List<Fill> fills = new ArrayList<>();
        int remaining = quantity;
        Iterator<Map.Entry<Integer, ArrayDeque<Resting>>> levels = opposite.entrySet().iterator();
        while (remaining > 0 && levels.hasNext()) {
            Map.Entry<Integer, ArrayDeque<Resting>> level = levels.next();
            if (!crosses(side, price, level.getKey())) {
                break;
            }
            ArrayDeque<Resting> queue = level.getValue();
            while (remaining > 0 && !queue.isEmpty()) {
                Resting maker = queue.peekFirst();
                int qty = Math.min(remaining, maker.remaining);
                fills.add(new Fill(maker.orderId, level.getKey(), qty));
                maker.remaining -= qty;
                remaining -= qty;
                if (maker.remaining == 0) {
                    queue.pollFirst();
                }
            }
            if (queue.isEmpty()) {
                levels.remove();
            }
        }
        if (remaining > 0) {
            rest(assetId, orderId, userId, side, price, remaining);
        }
        return fills;
    }

    public void remove(long assetId, long orderId) {
        AssetBook book = books.get(assetId);
        if (book == null) {
            return;
        }
        removeFrom(book.bids, orderId);
        removeFrom(book.asks, orderId);
    }

    // 테스트·진단용: 해당 방향의 가격대별 남은 수량
    public Map<Integer, Integer> depth(long assetId, OrderSide side) {
        Map<Integer, Integer> depth = new java.util.LinkedHashMap<>();
        AssetBook book = books.get(assetId);
        if (book != null) {
            book.side(side).forEach((p, q) -> depth.put(p, q.stream().mapToInt(r -> r.remaining).sum()));
        }
        return depth;
    }

    private static void removeFrom(NavigableMap<Integer, ArrayDeque<Resting>> side, long orderId) {
        Iterator<Map.Entry<Integer, ArrayDeque<Resting>>> levels = side.entrySet().iterator();
        while (levels.hasNext()) {
            ArrayDeque<Resting> queue = levels.next().getValue();
            if (queue.removeIf(r -> r.orderId == orderId) && queue.isEmpty()) {
                levels.remove();
            }
        }
    }

    private AssetBook book(long assetId) {
        return books.computeIfAbsent(assetId, id -> new AssetBook());
    }

    private static boolean crosses(OrderSide taker, int takerPrice, int makerPrice) {
        return taker == OrderSide.BUY ? makerPrice <= takerPrice : makerPrice >= takerPrice;
    }

    private static OrderSide opposite(OrderSide side) {
        return side == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
    }
}
