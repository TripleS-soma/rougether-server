package com.triples.rougether.adminapi.moderation.web;

import com.triples.rougether.adminapi.moderation.dto.BannedWordImportResult;
import com.triples.rougether.adminapi.moderation.dto.BannedWordResponse;
import com.triples.rougether.adminapi.moderation.error.BannedWordInvalidException;
import com.triples.rougether.adminapi.moderation.service.BannedWordAdminService;
import com.triples.rougether.common.error.ErrorResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 어드민 금칙어 관리 (#209). 화면(/banned-words)에서 단건 CRUD,
// 벌크 적재(deploy/seed/banned_words.json)는 seed 스크립트(curl)가 import 를 호출한다.
@RestController
@RequestMapping("/admin/banned-words")
public class BannedWordController {

    private final BannedWordAdminService bannedWordAdminService;

    public BannedWordController(BannedWordAdminService bannedWordAdminService) {
        this.bannedWordAdminService = bannedWordAdminService;
    }

    @GetMapping
    public Map<String, List<BannedWordResponse>> getAll() {
        return Map.of("items", bannedWordAdminService.getAll());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BannedWordResponse add(@RequestBody Map<String, String> request) {
        return bannedWordAdminService.add(request.get("word"));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        bannedWordAdminService.delete(id);
    }

    @PostMapping("/import")
    public BannedWordImportResult importWords(@RequestBody List<String> words) {
        return bannedWordAdminService.importWords(words);
    }

    @ExceptionHandler(BannedWordInvalidException.class)
    public ResponseEntity<ErrorResponse> handleInvalid(BannedWordInvalidException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(ErrorResponse.of("BANNED_WORD_INVALID", exception.getMessage()));
    }
}
