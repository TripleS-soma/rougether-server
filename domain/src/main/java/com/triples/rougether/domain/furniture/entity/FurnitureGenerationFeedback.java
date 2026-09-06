package com.triples.rougether.domain.furniture.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "furniture_generation_feedback")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FurnitureGenerationFeedback {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 36) private String jobId;
    @Column(nullable = false, length = 36) private String requestId;
    @Column(nullable = false, length = 500) private String content;
    @Column(nullable = false) private Instant createdAt;

    public FurnitureGenerationFeedback(String jobId, String requestId, String content, Instant now) {
        this.jobId = jobId;
        this.requestId = requestId;
        this.content = content;
        this.createdAt = now;
    }
}
