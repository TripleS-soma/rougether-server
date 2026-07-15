package com.triples.rougether.userapi.character.web;

import com.triples.rougether.userapi.character.dto.CharacterSelectRequest;
import com.triples.rougether.userapi.character.dto.CharacterSelectResponse;
import com.triples.rougether.userapi.character.dto.MyCharacterListResponse;
import com.triples.rougether.userapi.character.service.MyCharacterCommandService;
import com.triples.rougether.userapi.character.service.MyCharacterQueryService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Character", description = "캐릭터 관련 API")
@RestController
@RequestMapping("/api/v1/me/characters")
public class MyCharacterController {

    private final MyCharacterQueryService myCharacterQueryService;
    private final MyCharacterCommandService myCharacterCommandService;

    public MyCharacterController(MyCharacterQueryService myCharacterQueryService,
                                 MyCharacterCommandService myCharacterCommandService) {
        this.myCharacterQueryService = myCharacterQueryService;
        this.myCharacterCommandService = myCharacterCommandService;
    }

    @Operation(summary = "내 보유 캐릭터 목록 조회",
            description = "로그인한 회원이 보유한 캐릭터를 마스터 정렬(sortOrder) 순으로 반환합니다. "
                    + "획득 경로는 온보딩 무료 선택(1회)과 캐릭터 뽑기(POST /api/v1/gacha/{id}/draw)입니다. "
                    + "selected 가 true 인 항목이 현재 착용(방 화면 표시) 캐릭터이며 동시에 1개만 true 입니다. "
                    + "착용 교체는 PUT /api/v1/me/characters/select 에 이 응답의 characterId 를 보내면 됩니다. "
                    + "운영 회수(비활성)된 캐릭터는 보유 중이어도 목록에서 제외됩니다 (보유 이력 자체는 유지).")
    @GetMapping
    public MyCharacterListResponse getMyCharacters(@CurrentUser AuthUser user) {
        return myCharacterQueryService.getMyCharacters(user.id());
    }

    @Operation(summary = "착용 캐릭터 교체",
            description = "보유한 캐릭터로 착용(대표)을 교체합니다. 방 화면(내 방·집 멤버 방)에 표시되는 캐릭터가 바뀝니다. "
                    + "보유하지 않은 캐릭터를 지정하면 409(CHARACTER_NOT_OWNED)이며, 신규 획득은 온보딩 무료 선택(1회) "
                    + "또는 캐릭터 뽑기(POST /api/v1/gacha/{id}/draw)로만 가능합니다. "
                    + "이미 착용 중인 캐릭터를 지정하면 변경 없이 그대로 성공을 반환합니다.")
    @PutMapping("/select")
    public CharacterSelectResponse select(@CurrentUser AuthUser user,
                                          @Valid @RequestBody CharacterSelectRequest request) {
        return new CharacterSelectResponse(myCharacterCommandService.select(user.id(), request.characterId()));
    }
}
