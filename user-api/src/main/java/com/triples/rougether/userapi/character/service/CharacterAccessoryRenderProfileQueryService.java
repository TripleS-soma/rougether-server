package com.triples.rougether.userapi.character.service;

import com.triples.rougether.domain.character.entity.CharacterAccessoryRenderProfile;
import com.triples.rougether.domain.character.entity.UserCharacterAccessory;
import com.triples.rougether.domain.character.repository.CharacterAccessoryRenderProfileRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CharacterAccessoryRenderProfileQueryService {

    private final CharacterAccessoryRenderProfileRepository renderProfileRepository;

    public CharacterAccessoryRenderProfileQueryService(
            CharacterAccessoryRenderProfileRepository renderProfileRepository) {
        this.renderProfileRepository = renderProfileRepository;
    }

    // userCharacterId -> itemId -> 프로필 목록. 여러 방/캐릭터도 한 번의 프로필 쿼리로 조회함.
    public Map<Long, Map<Long, List<CharacterAccessoryRenderProfile>>> findFor(
            Collection<UserCharacterAccessory> accessories) {
        if (accessories.isEmpty()) {
            return Map.of();
        }
        List<Long> itemIds = accessories.stream()
                .map(accessory -> accessory.getUserItem().getItem().getId())
                .distinct()
                .toList();
        List<Long> characterIds = accessories.stream()
                .map(accessory -> accessory.getUserCharacter().getCharacter().getId())
                .distinct()
                .toList();

        Map<Long, Map<Long, List<CharacterAccessoryRenderProfile>>> byCharacterAndItem =
                renderProfileRepository.findByItemIdInAndCharacterIdIn(itemIds, characterIds)
                        .stream()
                        .collect(
                                Collectors.groupingBy(
                                        profile -> profile.getCharacter().getId(),
                                        LinkedHashMap::new,
                                        Collectors.groupingBy(
                                                profile -> profile.getItem().getId(),
                                                LinkedHashMap::new,
                                                Collectors.toList())));

        Map<Long, Map<Long, List<CharacterAccessoryRenderProfile>>> result = new HashMap<>();
        for (UserCharacterAccessory accessory : accessories) {
            Long characterId = accessory.getUserCharacter().getCharacter().getId();
            Long itemId = accessory.getUserItem().getItem().getId();
            List<CharacterAccessoryRenderProfile> profiles = byCharacterAndItem
                    .getOrDefault(characterId, Map.of())
                    .getOrDefault(itemId, List.of());
            result.computeIfAbsent(accessory.getUserCharacter().getId(), ignored -> new HashMap<>())
                    .put(itemId, profiles);
        }
        return result;
    }
}
