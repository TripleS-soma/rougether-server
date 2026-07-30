package com.triples.rougether.userapi.character.service;

import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.entity.UserCharacterAccessory;
import com.triples.rougether.domain.character.repository.UserCharacterAccessoryRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.userapi.character.dto.MyCharacterListResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 보유 캐릭터 조회. 획득 경로(온보딩 무료 1회·뽑기 지급)와 착용 변경은 각자의 도메인이 소유하고,
// 여기는 읽기 전용 목록만 담당한다.
@Service
public class MyCharacterQueryService {

    private final UserCharacterRepository userCharacterRepository;
    private final UserCharacterAccessoryRepository userCharacterAccessoryRepository;
    private final CharacterAccessoryRenderProfileQueryService renderProfileQueryService;

    public MyCharacterQueryService(
            UserCharacterRepository userCharacterRepository,
            UserCharacterAccessoryRepository userCharacterAccessoryRepository,
            CharacterAccessoryRenderProfileQueryService renderProfileQueryService) {
        this.userCharacterRepository = userCharacterRepository;
        this.userCharacterAccessoryRepository = userCharacterAccessoryRepository;
        this.renderProfileQueryService = renderProfileQueryService;
    }

    @Transactional(readOnly = true)
    public MyCharacterListResponse getMyCharacters(Long userId) {
        List<UserCharacter> owned = userCharacterRepository.findOwnedWithCharacter(userId);
        if (owned.isEmpty()) {
            return MyCharacterListResponse.of(List.of(), Map.of(), Map.of());
        }
        List<UserCharacterAccessory> accessories =
                userCharacterAccessoryRepository.findActiveByUserCharacterIdIn(
                        owned.stream().map(UserCharacter::getId).toList());
        Map<Long, List<UserCharacterAccessory>> accessoriesByUserCharacterId =
                accessories.stream()
                        .collect(Collectors.groupingBy(
                                accessory -> accessory.getUserCharacter().getId()));
        return MyCharacterListResponse.of(
                owned,
                accessoriesByUserCharacterId,
                renderProfileQueryService.findFor(accessories));
    }
}
