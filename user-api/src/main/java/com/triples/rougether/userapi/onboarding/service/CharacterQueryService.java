package com.triples.rougether.userapi.onboarding.service;

import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.userapi.onboarding.dto.CharacterListResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CharacterQueryService {

    private final CharacterRepository characterRepository;

    public CharacterQueryService(CharacterRepository characterRepository) {
        this.characterRepository = characterRepository;
    }

    @Transactional(readOnly = true)
    public CharacterListResponse getCharacters() {
        return CharacterListResponse.of(characterRepository.findByActiveTrueOrderBySortOrderAsc());
    }
}
