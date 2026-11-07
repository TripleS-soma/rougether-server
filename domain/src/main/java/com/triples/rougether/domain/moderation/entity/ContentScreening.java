package com.triples.rougether.domain.moderation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 자동 유해 판정 결과(#478). 대상당 1행이며 본문이 바뀌면(content_hash) 같은 행을 갱신함. 판정 원문은 저장하지 않음.
// targetType 은 FEED_POST·FEED_COMMENT 만 허용(DB CHECK). reportId 는 이 판정이 만든 자동 신고.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "content_screenings")
public class ContentScreening {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, updatable = false, length = 20)
    private ContentReportTargetType targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    // SHA-256 hex 고정 64자 — migration 의 CHAR(64) 와 맞추려 CHAR 로 매핑(ddl validate)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "model", nullable = false, length = 40)
    private String model;

    @Column(name = "harm_probability", nullable = false, precision = 5, scale = 4)
    private BigDecimal harmProbability;

    @Enumerated(EnumType.STRING)
    @Column(name = "top_reason", nullable = false, length = 20)
    private ContentReportReason topReason;

    // {"SPAM":0.01,...,"NONE":0.9} — Jev choice 확률 원본
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "probabilities", nullable = false)
    private String probabilities;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "flagged", nullable = false)
    private boolean flagged;

    @Column(name = "report_id")
    private Long reportId;

    @Column(name = "screened_at", nullable = false)
    private Instant screenedAt;

    public static ContentScreening first(ContentReportTargetType targetType, Long targetId) {
        ContentScreening screening = new ContentScreening();
        screening.targetType = targetType;
        screening.targetId = targetId;
        return screening;
    }

    // 새 판정으로 덮어씀. 이전 자동 신고 연결은 새 신고가 생길 때만 바뀜(linkReport).
    public void record(String contentHash, String model, BigDecimal harmProbability, ContentReportReason topReason,
                       String probabilities, int inputTokens, boolean flagged, Instant now) {
        this.contentHash = contentHash;
        this.model = model;
        this.harmProbability = harmProbability;
        this.topReason = topReason;
        this.probabilities = probabilities;
        this.inputTokens = inputTokens;
        this.flagged = flagged;
        this.screenedAt = now;
    }

    public void linkReport(Long reportId) {
        this.reportId = reportId;
    }
}
