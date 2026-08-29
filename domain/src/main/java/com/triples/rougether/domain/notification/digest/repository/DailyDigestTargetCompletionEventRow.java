package com.triples.rougether.domain.notification.digest.repository;

import java.time.Instant;

public interface DailyDigestTargetCompletionEventRow {

    Long getDigestId();

    Instant getCompletedAt();

    Instant getDigestSentAt();
}
