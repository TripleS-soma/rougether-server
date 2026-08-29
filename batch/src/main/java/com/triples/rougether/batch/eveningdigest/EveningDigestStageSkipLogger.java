package com.triples.rougether.batch.eveningdigest;

import com.triples.rougether.domain.member.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.listener.SkipListener;

@Slf4j
final class EveningDigestStageSkipLogger implements SkipListener<User, EveningDigestDraft> {

    @Override
    public void onSkipInRead(Throwable t) {
        log.warn("저녁 미완료 알림 reader skip", t);
    }

    @Override
    public void onSkipInProcess(User user, Throwable t) {
        log.warn("저녁 미완료 알림 process skip - userId={}", user.getId(), t);
    }

    @Override
    public void onSkipInWrite(EveningDigestDraft draft, Throwable t) {
        log.warn("저녁 미완료 알림 write skip - userId={}, targetDate={}",
                draft.user().getId(), draft.targetDate(), t);
    }
}
