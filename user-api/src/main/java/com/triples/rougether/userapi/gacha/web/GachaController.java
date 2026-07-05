package com.triples.rougether.userapi.gacha.web;

import com.triples.rougether.userapi.gacha.dto.GachaDrawRequest;
import com.triples.rougether.userapi.gacha.dto.GachaDrawResponse;
import com.triples.rougether.userapi.gacha.dto.GachaListResponse;
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

// 뽑기 조회 + 실행. 테마별 머신 목록/상세, 단챠·10연 뽑기.
@Tag(name = "Gacha", description = "뽑기 관련 API")
@RestController
@RequestMapping("/api/v1/gacha")
public class GachaController {

    private final GachaService gachaService;

    public GachaController(GachaService gachaService) {
        this.gachaService = gachaService;
    }

    @Operation(summary = "뽑기 머신 목록 조회", description = "운영 중인 뽑기 머신 목록을 반환합니다.")
    @GetMapping
    public GachaListResponse list() {
        return gachaService.getGachaList();
    }

    @Operation(summary = "뽑기 머신 상세 조회", description = "뽑기 머신 하나의 정보를 반환합니다.")
    @GetMapping("/{id}")
    public GachaResponse detail(@Parameter(description = "뽑기 머신 ID") @PathVariable Long id) {
        return gachaService.getGacha(id);
    }

    @Operation(summary = "뽑기 실행",
            description = "코인을 차감하고 아이템 또는 캐릭터를 뽑습니다. 10연은 단챠 비용의 5배입니다. "
                    + "이미 보유한 보상이 나오면 지급 대신 재화로 전환됩니다(아이템은 다이아 30, 캐릭터는 코인 200).")
    @PostMapping("/{id}/draw")
    public GachaDrawResponse draw(@CurrentUser AuthUser user,
                                  @Parameter(description = "뽑기 머신 ID") @PathVariable Long id,
                                  @Valid @RequestBody GachaDrawRequest request) {
        return gachaService.draw(user.id(), id, request);
    }
}
