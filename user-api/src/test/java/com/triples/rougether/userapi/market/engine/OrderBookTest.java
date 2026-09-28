package com.triples.rougether.userapi.market.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.market.entity.OrderSide;
import com.triples.rougether.userapi.market.engine.OrderBook.Fill;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrderBookTest {

    private static final long ASSET = 1L;
    private final OrderBook book = new OrderBook();

    @Test
    void 매수는_가장_싼_매도부터_먼저_걸린_가격으로_체결된다() {
        book.rest(ASSET, 10, 100, OrderSide.SELL, 32, 1);
        book.rest(ASSET, 11, 101, OrderSide.SELL, 28, 1);
        book.rest(ASSET, 12, 102, OrderSide.SELL, 30, 2);

        List<Fill> fills = book.match(ASSET, 20, 200, OrderSide.BUY, 30, 3);

        assertThat(fills).containsExactly(new Fill(11, 28, 1), new Fill(12, 30, 2));
        assertThat(book.depth(ASSET, OrderSide.SELL)).isEqualTo(Map.of(32, 1));
        assertThat(book.depth(ASSET, OrderSide.BUY)).isEmpty();
    }

    @Test
    void 같은_가격이면_먼저_들어온_주문이_먼저_체결된다() {
        book.rest(ASSET, 10, 100, OrderSide.SELL, 30, 1);
        book.rest(ASSET, 11, 101, OrderSide.SELL, 30, 1);

        assertThat(book.match(ASSET, 20, 200, OrderSide.BUY, 30, 1)).containsExactly(new Fill(10, 30, 1));
        assertThat(book.match(ASSET, 21, 201, OrderSide.BUY, 30, 1)).containsExactly(new Fill(11, 30, 1));
    }

    @Test
    void 다_못_채우면_남은_수량을_호가창에_올린다() {
        book.rest(ASSET, 10, 100, OrderSide.BUY, 25, 1);

        List<Fill> fills = book.match(ASSET, 20, 200, OrderSide.SELL, 25, 3);

        assertThat(fills).containsExactly(new Fill(10, 25, 1));
        assertThat(book.depth(ASSET, OrderSide.SELL)).isEqualTo(Map.of(25, 2));
    }

    @Test
    void 매도는_가장_비싼_매수부터_매수_가격으로_체결되고_가격이_안_맞으면_멈춘다() {
        book.rest(ASSET, 10, 100, OrderSide.BUY, 20, 1);
        book.rest(ASSET, 11, 101, OrderSide.BUY, 26, 1);

        List<Fill> fills = book.match(ASSET, 20, 200, OrderSide.SELL, 22, 2);

        assertThat(fills).containsExactly(new Fill(11, 26, 1));
        assertThat(book.depth(ASSET, OrderSide.SELL)).isEqualTo(Map.of(22, 1));
        assertThat(book.depth(ASSET, OrderSide.BUY)).isEqualTo(Map.of(20, 1));
    }

    @Test
    void 체결_가능한_반대편에_내_주문이_있으면_알려준다() {
        book.rest(ASSET, 10, 100, OrderSide.SELL, 30, 1);
        book.rest(ASSET, 11, 200, OrderSide.SELL, 40, 1);

        assertThat(book.crossesOwn(ASSET, 100, OrderSide.BUY, 30)).isTrue();
        assertThat(book.crossesOwn(ASSET, 200, OrderSide.BUY, 30)).isFalse(); // 40 은 가격이 안 맞음
        assertThat(book.crossesOwn(ASSET, 200, OrderSide.BUY, 40)).isTrue();
    }

    @Test
    void 취소된_주문은_호가창에서_빠진다() {
        book.rest(ASSET, 10, 100, OrderSide.SELL, 30, 1);
        book.remove(ASSET, 10);

        assertThat(book.match(ASSET, 20, 200, OrderSide.BUY, 30, 1)).isEmpty();
        assertThat(book.depth(ASSET, OrderSide.SELL)).isEmpty();
    }

    @Test
    void 다른_종목끼리는_체결되지_않는다() {
        book.rest(2L, 10, 100, OrderSide.SELL, 30, 1);

        assertThat(book.match(ASSET, 20, 200, OrderSide.BUY, 30, 1)).isEmpty();
    }
}
