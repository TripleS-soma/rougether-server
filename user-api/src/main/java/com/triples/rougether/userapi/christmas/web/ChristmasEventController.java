package com.triples.rougether.userapi.christmas.web;

import com.triples.rougether.userapi.christmas.dto.ChristmasEventResponse;
import com.triples.rougether.userapi.christmas.service.ChristmasEventService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/events/christmas")
@RequiredArgsConstructor
public class ChristmasEventController {
    private final ChristmasEventService service;

    @GetMapping
    @Operation(summary = "크리스마스 이벤트와 팝업 숨김 상태 조회")
    public ChristmasEventResponse get(@CurrentUser AuthUser user) {
        return service.get(user.id());
    }

    @PostMapping("/popup-dismissal")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "이 계정의 크리스마스 팝업 다시 보지 않기")
    public void dismiss(@CurrentUser AuthUser user) {
        service.dismissPopup(user.id());
    }
}
