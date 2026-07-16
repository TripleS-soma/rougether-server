package com.triples.rougether.userapi.character.service;

import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.userapi.character.dto.MyCharacterListResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 보유 캐릭터 조회. 획득 경로(온보딩 무료 1회·뽑기 지급)와 착용 변경은 각자의 도메인이 소유하고,
// 여기는 읽기 전용 목록만 담당한다.
@Service
public class MyCharacterQueryService {

    private final UserCharacterRepository userCharacterRepository;

    public MyCharacterQueryService(UserCharacterRepository userCharacterRepository) {
        this.userCharacterRepository = userCharacterRepository;
    }

    @Transactional(readOnly = true)
    public MyCharacterListResponse getMyCharacters(Long userId) {
        return MyCharacterListResponse.of(userCharacterRepository.findOwnedWithCharacter(userId));
    }
}
