package com.triples.rougether.batch.eveningdigest;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.data.domain.PageRequest;

@RequiredArgsConstructor
class EveningDigestUserReader implements ItemReader<User> {

    private static final int PAGE_SIZE = 200;

    private final UserRepository userRepository;
    private final LocalDate targetDate;
    private final Instant dayEndExclusive;

    private Iterator<User> currentBatch = Collections.emptyIterator();
    private long cursorId;
    private boolean exhausted;

    @Override
    public User read() {
        if (!currentBatch.hasNext() && !exhausted) {
            List<User> batch = userRepository.findDailyIncompleteDigestCandidates(
                    targetDate, dayEndExclusive, cursorId, PageRequest.of(0, PAGE_SIZE));
            if (batch.isEmpty()) {
                exhausted = true;
            } else {
                currentBatch = batch.iterator();
            }
        }
        if (!currentBatch.hasNext()) {
            return null;
        }
        User next = currentBatch.next();
        cursorId = next.getId();
        return next;
    }
}
