package com.triples.rougether.adminapi.catalog.service;

import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.gacha.repository.GachaRepository;
import com.triples.rougether.domain.goal.repository.GoalRepository;
import com.triples.rougether.domain.i18n.LocalizedName;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import java.util.Map;
import java.util.NoSuchElementException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogTranslationService {
    public enum Kind { items, themes, characters, goals, gacha }
    public record Translation(String name, Map<String, String> nameTranslations) {}

    private final ItemRepository items;
    private final ThemeRepository themes;
    private final CharacterRepository characters;
    private final GoalRepository goals;
    private final GachaRepository gacha;

    public Translation get(Kind kind, Long id) { return response(find(kind, id)); }

    @Transactional
    public Translation update(Kind kind, Long id, Map<String, String> translations) {
        LocalizedName target = find(kind, id);
        target.updateNameTranslations(translations);
        return response(target);
    }

    private Translation response(LocalizedName target) {
        return new Translation(target.getName(), target.getNameTranslations() == null ? Map.of() : target.getNameTranslations());
    }

    private LocalizedName find(Kind kind, Long id) {
        return switch (kind) {
            case items -> items.findById(id).orElseThrow(NoSuchElementException::new);
            case themes -> themes.findById(id).orElseThrow(NoSuchElementException::new);
            case characters -> characters.findById(id).orElseThrow(NoSuchElementException::new);
            case goals -> goals.findById(id).orElseThrow(NoSuchElementException::new);
            case gacha -> gacha.findById(id).orElseThrow(NoSuchElementException::new);
        };
    }
}
