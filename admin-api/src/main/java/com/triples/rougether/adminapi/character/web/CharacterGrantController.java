package com.triples.rougether.adminapi.character.web;

import com.triples.rougether.adminapi.character.dto.CharacterGrantRequest;
import com.triples.rougether.adminapi.character.dto.CharacterGrantResponse;
import com.triples.rougether.adminapi.character.service.CharacterGrantService;
import com.triples.rougether.common.error.ErrorResponse;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import jakarta.validation.Valid;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// 개발/QA용 캐릭터 지급. 어드민 인증 필요, 화면·curl 겸용(CSRF 제외 경로).
@RestController
public class CharacterGrantController {

    private final CharacterGrantService characterGrantService;
    private final CharacterRepository characterRepository;

    public CharacterGrantController(CharacterGrantService characterGrantService,
                                    CharacterRepository characterRepository) {
        this.characterGrantService = characterGrantService;
        this.characterRepository = characterRepository;
    }

    @PostMapping("/admin/users/{userId}/characters/grant")
    public CharacterGrantResponse grant(@PathVariable Long userId,
                                        @Valid @RequestBody CharacterGrantRequest request) {
        return characterGrantService.grant(userId, request.characterCode());
    }

    // 지급 화면의 캐릭터 select 채우기용 (활성만, 마스터 정렬순)
    @GetMapping("/admin/characters")
    public List<CharacterOption> characters() {
        return characterRepository.findByActiveTrueOrderBySortOrderAsc().stream()
                .map(c -> new CharacterOption(c.getId(), c.getCode(), c.getName()))
                .toList();
    }

    public record CharacterOption(Long id, String code, String name) {
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("USER_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleInvalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("CHARACTER_GRANT_INVALID", exception.getMessage()));
    }
}
