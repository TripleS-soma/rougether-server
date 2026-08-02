package com.triples.rougether.adminapi.moderation.service;

import com.triples.rougether.adminapi.moderation.dto.BannedWordImportResult;
import com.triples.rougether.adminapi.moderation.dto.BannedWordResponse;
import com.triples.rougether.adminapi.moderation.error.BannedWordInvalidException;
import com.triples.rougether.common.text.TextNormalizer;
import com.triples.rougether.domain.moderation.entity.BannedWord;
import com.triples.rougether.domain.moderation.repository.BannedWordRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 금칙어 관리 (#209). 저장은 항상 정규화(TextNormalizer) 결과 - user-api 매칭과 같은 형태 유지.
// 벌크 적재(deploy/seed/banned_words.json)는 이미 있는 단어 skip 으로 멱등.
@Service
public class BannedWordAdminService {

    private final BannedWordRepository bannedWordRepository;

    public BannedWordAdminService(BannedWordRepository bannedWordRepository) {
        this.bannedWordRepository = bannedWordRepository;
    }

    @Transactional(readOnly = true)
    public List<BannedWordResponse> getAll() {
        return bannedWordRepository.findAllByOrderByWordAsc().stream()
                .map(BannedWordResponse::of)
                .toList();
    }

    @Transactional
    public BannedWordResponse add(String word) {
        String normalized = normalizeOrThrow(word);
        if (bannedWordRepository.existsByWord(normalized)) {
            throw new BannedWordInvalidException("이미 등록된 금칙어입니다: " + normalized, 409);
        }
        try {
            return BannedWordResponse.of(bannedWordRepository.saveAndFlush(BannedWord.of(normalized)));
        } catch (DataIntegrityViolationException e) {
            // 동시 등록 경합 - UNIQUE 가 최후 방어선
            throw new BannedWordInvalidException("이미 등록된 금칙어입니다: " + normalized, 409);
        }
    }

    @Transactional
    public void delete(Long id) {
        if (!bannedWordRepository.existsById(id)) {
            throw new BannedWordInvalidException("존재하지 않는 금칙어입니다: " + id, 404);
        }
        bannedWordRepository.deleteById(id);
    }

    @Transactional
    public BannedWordImportResult importWords(List<String> words) {
        int added = 0;
        List<String> skipped = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (String word : words) {
            String normalized = TextNormalizer.normalize(word);
            if (normalized.isEmpty() || normalized.length() > 50) {
                invalid.add(word);
                continue;
            }
            if (bannedWordRepository.existsByWord(normalized)) {
                skipped.add(normalized);
                continue;
            }
            // 동시 import 경합은 UNIQUE 가 커밋 시점에 거른다 - 트랜잭션 내 catch-후-계속은
            // rollback-only 로 커밋이 깨지므로 쓰지 않는다(관리자 1인 순차 실행 전제, 재실행 멱등).
            bannedWordRepository.save(BannedWord.of(normalized));
            added++;
        }
        return new BannedWordImportResult(added, skipped.size(), List.copyOf(invalid));
    }

    private String normalizeOrThrow(String word) {
        String normalized = TextNormalizer.normalize(word);
        if (normalized.isEmpty()) {
            throw new BannedWordInvalidException("정규화 결과가 비어 있어 등록할 수 없습니다.", 400);
        }
        if (normalized.length() > 50) {
            throw new BannedWordInvalidException("금칙어는 정규화 기준 50자 이하여야 합니다.", 400);
        }
        return normalized;
    }
}
