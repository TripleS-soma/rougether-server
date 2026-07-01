package com.triples.rougether.userapi.gacha.web;

import com.triples.rougether.userapi.gacha.dto.GachaDrawRequest;
import com.triples.rougether.userapi.gacha.dto.GachaDrawResponse;
import com.triples.rougether.userapi.gacha.dto.GachaListResponse;
import com.triples.rougether.userapi.gacha.dto.GachaResponse;
import com.triples.rougether.userapi.gacha.service.GachaService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 뽑기 조회 + 실행. 테마별 머신 목록/상세, 단챠·10연 뽑기.
@RestController
@RequestMapping("/api/v1/gacha")
public class GachaController {

    private final GachaService gachaService;

    public GachaController(GachaService gachaService) {
        this.gachaService = gachaService;
    }

    @GetMapping
    public GachaListResponse list() {
        return gachaService.getGachaList();
    }

    @GetMapping("/{id}")
    public GachaResponse detail(@PathVariable Long id) {
        return gachaService.getGacha(id);
    }

    @PostMapping("/{id}/draw")
    public GachaDrawResponse draw(@CurrentUser AuthUser user,
                                  @PathVariable Long id,
                                  @Valid @RequestBody GachaDrawRequest request) {
        return gachaService.draw(user.id(), id, request);
    }
}
