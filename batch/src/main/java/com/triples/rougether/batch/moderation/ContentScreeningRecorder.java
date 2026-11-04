package com.triples.rougether.batch.moderation;

import com.triples.rougether.domain.moderation.entity.ContentReport;
import com.triples.rougether.domain.moderation.entity.ContentReportStatus;
import com.triples.rougether.domain.moderation.entity.ContentReportTargetType;
import com.triples.rougether.domain.moderation.entity.ContentScreening;
import com.triples.rougether.domain.moderation.repository.ContentReportRepository;
import com.triples.rougether.domain.moderation.repository.ContentScreeningRepository;
import com.triples.rougether.domain.moderation.repository.ScreeningCandidate;
import java.time.Instant;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// 판정 저장과 자동 신고를 한 트랜잭션으로 커밋함(#478).
// 후보는 미판정이거나 본문이 바뀐 대상뿐이라, 여기 도달한 판정은 "처음" 또는 "수정 후"임 — 기준 이상이면 신고 대상.
// 단 같은 대상에 처리 대기(RECEIVED) 신고가 이미 있으면(사용자·이전 자동 신고) 판정만 저장하고 신고는 만들지 않음.
// 사용자 신고로 들어온 대상(reported, #479)은 그 사이 신고가 처리됐더라도 자동 신고를 만들지 않음 — 점수 보충용 판정임.
@Service
@RequiredArgsConstructor
public class ContentScreeningRecorder {

    private final ContentScreeningRepository screenings;
    private final ContentReportRepository reports;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Transactional
    public ContentScreening record(ScreeningTarget target, String contentHash, JevVerdict verdict,
                                   ScreeningJudgement judgement, Instant now) {
        ScreeningCandidate candidate = target.candidate();
        ContentReportTargetType type = ContentReportTargetType.valueOf(candidate.getTargetType());
        ContentScreening screening = screenings.findForUpdate(type, candidate.getTargetId())
                .orElseGet(() -> ContentScreening.first(type, candidate.getTargetId()));
        screening.record(contentHash, verdict.model(), judgement.harmProbability(), judgement.topReason(),
                objectMapper.writeValueAsString(new TreeMap<>(verdict.probabilities())), verdict.inputTokens(),
                judgement.flagged(), now);
        if (judgement.flagged() && !target.reported()
                && !reports.existsByTargetTypeAndTargetIdAndStatus(type, candidate.getTargetId(),
                ContentReportStatus.RECEIVED)) {
            ContentReport report = reports.save(ContentReport.auto(type, candidate.getTargetId(),
                    candidate.getOwnerUserId(), judgement.topReason(), judgement.detail(), now));
            screening.linkReport(report.getId());
        }
        return screenings.save(screening);
    }
}
