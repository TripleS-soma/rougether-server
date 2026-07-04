package com.triples.rougether.userapi.house.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.house.dto.HouseCreateRequest;
import com.triples.rougether.userapi.house.dto.HouseCreateResponse;
import com.triples.rougether.userapi.house.service.HouseCommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 공동집 API. 생성자는 OWNER 로 즉시 등록되고 초대코드가 발급된다.
@Tag(name = "House", description = "공동집 관련 API")
@RestController
@RequestMapping("/api/v1/houses")
public class HouseController {

    private final HouseCommandService houseCommandService;

    public HouseController(HouseCommandService houseCommandService) {
        this.houseCommandService = houseCommandService;
    }

    @Operation(summary = "공동집 생성",
            description = "새 공동집을 만들고 생성자를 OWNER 구성원으로 등록하며 초대코드를 발급합니다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HouseCreateResponse create(@CurrentUser AuthUser user,
                                      @Valid @RequestBody HouseCreateRequest request) {
        return houseCommandService.create(user.id(), request);
    }
}
