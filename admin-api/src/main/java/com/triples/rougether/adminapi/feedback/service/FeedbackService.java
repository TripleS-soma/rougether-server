package com.triples.rougether.adminapi.feedback.service;

import com.triples.rougether.domain.feedback.entity.AppUpdate;
import com.triples.rougether.domain.feedback.entity.Suggestion;
import com.triples.rougether.domain.feedback.repository.AppUpdateRepository;
import com.triples.rougether.domain.feedback.repository.SuggestionRepository;
import com.triples.rougether.adminapi.feedback.dto.*;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class FeedbackService {
    private static final int PAGE_SIZE = 20;
    private final SuggestionRepository suggestions;
    private final AppUpdateRepository updates;

    public FeedbackService(SuggestionRepository suggestions, AppUpdateRepository updates) {
        this.suggestions = suggestions;
        this.updates = updates;
    }

    public FeedbackPage<AppUpdateResponse> updates(Long cursor) {
        List<AppUpdate> found = updates.findByIdLessThanOrderByIdDesc(
                cursor == null ? Long.MAX_VALUE : cursor, PageRequest.of(0, PAGE_SIZE + 1));
        List<AppUpdate> page = found.stream().limit(PAGE_SIZE).toList();
        return new FeedbackPage<>(page.stream().map(AppUpdateResponse::of).toList(),
                found.size() > PAGE_SIZE ? page.getLast().getId() : null);
    }

    public FeedbackPage<SuggestionResponse> suggestions(Long cursor) {
        List<Suggestion> found = suggestions.findByIdLessThanOrderByIdDesc(
                cursor == null ? Long.MAX_VALUE : cursor, PageRequest.of(0, PAGE_SIZE + 1));
        List<Suggestion> page = found.stream().limit(PAGE_SIZE).toList();
        return new FeedbackPage<>(page.stream().map(SuggestionResponse::of).toList(),
                found.size() > PAGE_SIZE ? page.getLast().getId() : null);
    }

    @Transactional
    public AppUpdateResponse publish(String title, String content) {
        return AppUpdateResponse.of(updates.save(AppUpdate.create(title, content)));
    }

    @Transactional
    public void deleteUpdate(Long id) { updates.deleteById(id); }

    @Transactional
    public void deleteSuggestion(Long id) { suggestions.deleteById(id); }
}
