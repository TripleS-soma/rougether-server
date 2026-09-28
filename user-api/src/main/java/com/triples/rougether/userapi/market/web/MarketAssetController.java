package com.triples.rougether.userapi.market.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.market.dto.MarketAssetIssueRequest;
import com.triples.rougether.userapi.market.dto.MarketAssetListResponse;
import com.triples.rougether.userapi.market.dto.MarketAssetResponse;
import com.triples.rougether.userapi.market.dto.MarketTradeListResponse;
import com.triples.rougether.userapi.market.service.MarketAssetService;
import com.triples.rougether.userapi.market.service.MarketQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 가구 거래소 종목 발행·목록·상세·체결 조회.
@Tag(name = "Market", description = "가구 거래소 API")
@RestController
@RequestMapping("/api/v1/market/assets")
public class MarketAssetController {

    private final MarketAssetService marketAssetService;
    private final MarketQueryService marketQueryService;

    public MarketAssetController(MarketAssetService marketAssetService, MarketQueryService marketQueryService) {
        this.marketAssetService = marketAssetService;
        this.marketQueryService = marketQueryService;
    }

    @Operation(summary = "거래소 종목 목록 조회",
            description = "거래 중인(ACTIVE) 종목을 최근 상장순으로 한 페이지씩 조회합니다. 종목마다 가장 싼 판매 가격, "
                    + "판매 대기 수량, 최근 체결가를 함께 내려줍니다. 미지정 시 page=0, size=20 으로 조회합니다.")
    @GetMapping
    public MarketAssetListResponse list(
            @Parameter(description = "페이지 번호 (0부터)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기 (1~100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return marketQueryService.listAssets(page, size);
    }

    @Operation(summary = "거래소 종목 상세 조회",
            description = "종목 정보와 호가를 조회합니다. 호가는 대기 중인 주문의 남은 수량을 가격대별로 합산하며, "
                    + "판매 호가(asks)는 싼 가격부터, 구매 호가(bids)는 비싼 가격부터 각 최대 10단계를 내려줍니다. "
                    + "owned 는 내가 이 가구를 인벤토리에 보유 중인지(판매 등록으로 맡긴 것은 제외), isCreator 는 내가 제작자인지입니다.")
    @GetMapping("/{assetId}")
    public MarketAssetResponse detail(@CurrentUser AuthUser user,
                                      @Parameter(description = "종목 ID. 종목 목록 응답의 assetId 값")
                                      @PathVariable Long assetId) {
        return marketQueryService.getAsset(user.id(), assetId);
    }

    @Operation(summary = "거래소 최근 체결 조회",
            description = "종목의 체결 내역을 최신순으로 한 페이지씩 조회합니다. 미지정 시 page=0, size=20 으로 조회합니다.")
    @GetMapping("/{assetId}/trades")
    public MarketTradeListResponse trades(
            @Parameter(description = "종목 ID. 종목 목록 응답의 assetId 값") @PathVariable Long assetId,
            @Parameter(description = "페이지 번호 (0부터)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기 (1~100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return marketQueryService.trades(assetId, page, size);
    }

    @Operation(summary = "AI 가구 발행",
            description = "사진으로 만든 내 AI 가구를 거래소에 에디션으로 발행합니다. 가구를 만든 회원이 인벤토리에 보유 중인 가구만, 가구당 한 번 발행할 수 있습니다. "
                    + "발행 수량(1~10)에는 내가 가진 1개가 포함되고, 나머지는 발행 재고(unissuedQuantity)가 되어 판매 주문으로 팔 수 있습니다. "
                    + "발행 즉시 거래가 열리며 수량은 바꿀 수 없습니다.")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public MarketAssetResponse issue(@CurrentUser AuthUser user,
                                     @Valid @RequestBody MarketAssetIssueRequest request) {
        return marketAssetService.issue(user.id(), request.userItemId(), request.totalSupply());
    }
}
