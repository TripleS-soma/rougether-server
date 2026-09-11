package com.triples.rougether.furniture.lambda;

import com.triples.rougether.domain.furniture.repository.*;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class FurnitureLambdaOutbox {
    private final FurnitureLambdaExecutionRepository executions;
    private final FurnitureGenerationJobRepository jobs;
    private final FurnitureWorkerCapacityRepository capacity;
    private final Clock clock;
    public record Dispatch(String executionId, String jobId, String token) { }
    @Transactional(readOnly = true)
    public List<String> pending() {
        var limit = capacity.findById(1L).orElseThrow();
        if (!"LAMBDA".equals(limit.getExecutionMode()) || !limit.isExecutionEnabled()) return List.of();
        return executions.pending(clock.instant(), PageRequest.of(0, 10));
    }
    public Dispatch reserve(String id) {
        // job을 먼저 잠가 제어 명령과 동일한 순서를 유지함.
        String jobId = executions.findJobId(id).orElse(null);
        if (jobId == null) return null;
        var job = jobs.findForUpdate(jobId).orElse(null);
        var execution = executions.lock(id).orElse(null);
        if (execution == null) return null;
        if (job == null || job.terminal() || !id.equals(job.getExecutionId())) { execution.finish(); return null; }
        String token = execution.reservePublish(clock.instant());
        return token == null ? null : new Dispatch(id, job.getId(), token);
    }
    public void sent(Dispatch dispatch) { executions.lock(dispatch.executionId()).ifPresent(e -> e.sent(dispatch.token())); }
}
