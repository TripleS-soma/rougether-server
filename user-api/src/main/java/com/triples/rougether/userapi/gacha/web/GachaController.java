package com.triples.rougether.userapi.gacha.web;

import com.triples.rougether.userapi.gacha.dto.GachaDrawRequest;
import com.triples.rougether.userapi.gacha.dto.GachaDrawResponse;
import com.triples.rougether.userapi.gacha.dto.GachaListResponse;
import com.triples.rougether.userapi.gacha.dto.GachaRewardListResponse;
import com.triples.rougether.userapi.gacha.dto.GachaResponse;
import com.triples.rougether.userapi.gacha.service.GachaService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 뽑기 조회 + 실행. 테마별 머신 목록/상세, 단챠·5+1회 뽑기.
@Tag(name = "Gacha", description = "뽑기 관련 API")
@RestController
@RequestMapping("/api/v1/gacha")
public class GachaController {

    private final GachaService gachaService;

    public GachaController(GachaService gachaService) {
        this.gachaService = gachaService;
    }

    @Operation(summary = "뽑기 머신 목록 조회",
            description = "운영 중(active)인 뽑기 머신 목록을 반환합니다. "
                    + "themeId 가 있는 머신은 해당 테마의 가구 뽑기, null 이면 캐릭터 뽑기입니다.")
    @GetMapping
    public GachaListResponse list() {
        return gachaService.getGachaList();
    }

    @Operation(summary = "뽑기 머신 상세 조회",
            description = "뽑기 머신 하나의 정보를 반환합니다. id 는 뽑기 머신 목록 조회 응답의 gachaId 값을 사용합니다.")
    @GetMapping("/{id}")
    public GachaResponse detail(
            @Parameter(description = "뽑기 머신 ID. GET /api/v1/gacha (뽑기 머신 목록) 응답의 gachaId 값")
            @PathVariable Long id) {
        return gachaService.getGacha(id);
    }

    @Operation(summary = "뽑기 보상 목록 조회",
            description = "현재 활성 풀에 등록된 아이템·캐릭터의 이름, 이미지 asset key, 등급, "
                    + "인증 사용자의 보유 여부를 반환합니다. 추첨 weight 와 계산 확률은 노출하지 않습니다.")
    @GetMapping("/{id}/rewards")
    public GachaRewardListResponse rewards(
            @CurrentUser AuthUser user,
            @Parameter(description = "뽑기 머신 ID. GET /api/v1/gacha 응답의 gachaId 값")
            @PathVariable Long id) {
        return gachaService.getRewards(user.id(), id);
    }

    @Operation(summary = "뽑기 실행",
            description = "코인(COIN)을 차감하고 아이템 또는 캐릭터를 뽑습니다. count 는 1(단챠) 또는 6(5+1회)만 허용하며, "
                    + "5+1회 비용은 단챠 비용(costAmount)의 5배입니다. "
                    + "구버전 앱의 count=10 요청도 이행 기간에는 5+1회와 동일하게 결과 6개·비용 5배로 처리합니다. "
                    + "추첨은 등급을 먼저 정하고(일반 70%·희귀 25%·전설 5%) 해당 등급 풀 안에서 균등 확률로 뽑습니다"
                    + "(해당 등급 풀이 비어 있으면 전체 풀에서 균등). "
                    + "뽑힌 아이템은 즉시 인벤토리(GET /api/v1/me/items)에, 캐릭터는 보유 캐릭터에 지급됩니다. "
                    + "이미 보유한 보상이 나오면 지급 대신 재화로 전환됩니다(아이템은 다이아 3, 캐릭터는 코인 100). "
                    + "5+1회 뽑기 도중 새로 지급된 보상이 같은 묶음에서 다시 나와도 중복 전환으로 처리됩니다. "
                    + "응답에는 뽑은 순서대로의 결과 목록과 차감·전환 반영 후 코인·다이아 잔액이 함께 내려갑니다. "
                    + "id 는 뽑기 머신 목록 조회 응답의 gachaId 값을 사용합니다.")
    @PostMapping("/{id}/draw")
    public GachaDrawResponse draw(@CurrentUser AuthUser user,
                                  @Parameter(description = "뽑기 머신 ID. GET /api/v1/gacha (뽑기 머신 목록) 응답의 gachaId 값")
                                  @PathVariable Long id,
                                  @Valid @RequestBody GachaDrawRequest request) {
        return gachaService.draw(user.id(), id, request);
    }
}
