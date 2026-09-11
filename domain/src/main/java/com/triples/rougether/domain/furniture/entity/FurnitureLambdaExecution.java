package com.triples.rougether.domain.furniture.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

/** SQS outbox와 실행 소유권, 마지막 단계 응답을 같은 행에서 관리함. */
@Entity
@Table(name = "furniture_lambda_execution")
@Getter
public class FurnitureLambdaExecution {
    public enum State { PENDING, SENT, RUNNING, FINISHED }
    @Id @Column(length = 36) private String id;
    @Column(nullable = false, length = 36) private String jobId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private State state;
    @Column(length = 100) private String owner;
    private Instant deadline;
    @Column(nullable = false) private int sequence;
    @Column(length = 64) private String commandHash;
    @Column(columnDefinition = "text") private String replyJson;
    @Column(length = 36) private String publishToken;
    @Column(nullable = false) private Instant publishAfter;
    @Column(nullable = false) private Instant createdAt;
    protected FurnitureLambdaExecution() { }
    public FurnitureLambdaExecution(String jobId, Instant now) {
        this.id = UUID.randomUUID().toString(); this.jobId = jobId;
        this.state = State.PENDING; this.sequence = -1;
        this.publishAfter = now; this.createdAt = now;
    }
    public String reservePublish(Instant now) {
        if (state != State.PENDING || publishAfter.isAfter(now)) return null;
        publishToken = UUID.randomUUID().toString(); publishAfter = now.plusSeconds(60);
        return publishToken;
    }
    public void sent(String token) {
        if (state == State.PENDING && token.equals(publishToken)) state = State.SENT;
    }
    public void start(String owner, Instant deadline) {
        this.owner = owner; this.deadline = deadline; this.state = State.RUNNING;
    }
    public void remember(int sequence, String hash, String reply) {
        this.sequence = sequence; this.commandHash = hash; this.replyJson = reply;
    }
    public void finish() { state = State.FINISHED; }
}
