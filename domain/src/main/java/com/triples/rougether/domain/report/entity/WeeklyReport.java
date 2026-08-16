package com.triples.rougether.domain.report.entity;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.support.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// AI 주간 회고. 주 경계는 일~토(KST), 사용자·주당 1건(unique). 통계·섹션은 배치가 만든 JSON 문자열을 그대로 보관한다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "weekly_reports")
public class WeeklyReport extends BaseCreatedEntity {

    public static final int SUMMARY_MAX_LENGTH = 600;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "week_start_date", nullable = false)
    private LocalDate weekStartDate;

    @Column(name = "week_end_date", nullable = false)
    private LocalDate weekEndDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private WeeklyReportStatus status;

    // 생성에 쓴 LLM 모델 id. FALLBACK이면 null.
    @Column(name = "model", length = 100)
    private String model;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stats_json", nullable = false)
    private String statsJson;

    @Column(name = "summary", length = SUMMARY_MAX_LENGTH, nullable = false)
    private String summary;

    // {"highlights":[],"failurePatterns":[],"suggestions":[]}
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sections_json", nullable = false)
    private String sectionsJson;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    private WeeklyReport(User user, LocalDate weekStartDate, LocalDate weekEndDate, WeeklyReportStatus status,
                         String model, String statsJson, String summary, String sectionsJson, Instant generatedAt) {
        this.user = user;
        this.weekStartDate = weekStartDate;
        this.weekEndDate = weekEndDate;
        this.status = status;
        this.model = model;
        this.statsJson = statsJson;
        this.summary = summary;
        this.sectionsJson = sectionsJson;
        this.generatedAt = generatedAt;
    }

    public static WeeklyReport generated(User user, LocalDate weekStartDate, LocalDate weekEndDate, String model,
                                         String statsJson, String summary, String sectionsJson, Instant generatedAt) {
        return new WeeklyReport(user, weekStartDate, weekEndDate, WeeklyReportStatus.GENERATED, model,
                statsJson, summary, sectionsJson, generatedAt);
    }

    public static WeeklyReport fallback(User user, LocalDate weekStartDate, LocalDate weekEndDate,
                                        String statsJson, String summary, String sectionsJson, Instant generatedAt) {
        return new WeeklyReport(user, weekStartDate, weekEndDate, WeeklyReportStatus.FALLBACK, null,
                statsJson, summary, sectionsJson, generatedAt);
    }
}
