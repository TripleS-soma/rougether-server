package com.triples.rougether.batch.eveningdigest;

import com.triples.rougether.domain.notification.digest.entity.DailyIncompleteDigest;
import com.triples.rougether.domain.notification.digest.entity.DailyIncompleteDigestTarget;
import com.triples.rougether.domain.notification.digest.repository.DailyIncompleteDigestRepository;
import com.triples.rougether.domain.notification.digest.repository.DailyIncompleteDigestTargetRepository;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

@RequiredArgsConstructor
final class EveningDigestStageWriter implements ItemWriter<EveningDigestDraft> {

    private final DailyIncompleteDigestRepository digestRepository;
    private final DailyIncompleteDigestTargetRepository targetRepository;
    private final NotificationRepository notificationRepository;

    @Override
    public void write(Chunk<? extends EveningDigestDraft> chunk) {
        for (EveningDigestDraft draft : chunk) {
            stage(draft);
        }
    }

    private void stage(EveningDigestDraft draft) {
        if (digestRepository.existsByUserIdAndDigestDate(draft.user().getId(), draft.targetDate())) {
            return;
        }

        DailyIncompleteDigest digest = digestRepository.save(DailyIncompleteDigest.create(
                draft.user(), draft.targetDate(), draft.routineCount(), draft.todoCount()));
        List<DailyIncompleteDigestTarget> targets = new ArrayList<>(draft.routineCount() + draft.todoCount());
        draft.routineLineageIds().forEach(
                lineageId -> targets.add(DailyIncompleteDigestTarget.routine(digest, lineageId)));
        draft.todoIds().forEach(todoId -> targets.add(DailyIncompleteDigestTarget.todo(digest, todoId)));
        targetRepository.saveAll(targets);

        Notification notification = notificationRepository.save(Notification.create(
                draft.user(),
                NotificationType.DAILY_INCOMPLETE_DIGEST,
                EveningDigestMessage.TITLE,
                EveningDigestMessage.body(draft.routineCount(), draft.todoCount()),
                digest.getId()));
        digest.linkNotification(notification);
    }
}
