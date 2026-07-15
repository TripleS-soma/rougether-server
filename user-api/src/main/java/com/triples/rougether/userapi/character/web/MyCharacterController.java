package com.triples.rougether.userapi.character.web;

import com.triples.rougether.userapi.character.dto.MyCharacterListResponse;
import com.triples.rougether.userapi.character.service.MyCharacterQueryService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Character", description = "캐릭터 관련 API")
@RestController
@RequestMapping("/api/v1/me/characters")
public class MyCharacterController {

    private final MyCharacterQueryService myCharacterQueryService;

    public MyCharacterController(MyCharacterQueryService myCharacterQueryService) {
        this.myCharacterQueryService = myCharacterQueryService;
    }

    @Operation(summary = "내 보유 캐릭터 목록 조회",
            description = "로그인한 회원이 보유한 캐릭터를 마스터 정렬(sortOrder) 순으로 반환합니다. "
                    + "획득 경로는 온보딩 무료 선택(1회)과 캐릭터 뽑기(POST /api/v1/gacha/{id}/draw)입니다. "
                    + "selected 가 true 인 항목이 현재 착용(방 화면 표시) 캐릭터이며 동시에 1개만 true 입니다. "
                    + "착용 교체는 PUT /api/v1/onboarding/character 에 이 응답의 characterId 를 보내면 됩니다 "
                    + "(보유한 캐릭터만 교체 가능, 미보유 지정 시 CHARACTER_NOT_OWNED). "
                    + "비활성화된 캐릭터도 보유 중이면 목록에 포함됩니다.")
    @GetMapping
    public MyCharacterListResponse getMyCharacters(@CurrentUser AuthUser user) {
        return myCharacterQueryService.getMyCharacters(user.id());
    }
}
