package com.triples.rougether.batch.eveningdigest;

import com.triples.rougether.domain.member.entity.User;
import java.time.LocalDate;
import java.util.List;

record EveningDigestDraft(
        User user,
        LocalDate targetDate,
        List<Long> routineLineageIds,
        List<Long> todoIds) {

    EveningDigestDraft {
        routineLineageIds = List.copyOf(routineLineageIds);
        todoIds = List.copyOf(todoIds);
    }

    int routineCount() {
        return routineLineageIds.size();
    }

    int todoCount() {
        return todoIds.size();
    }
}
