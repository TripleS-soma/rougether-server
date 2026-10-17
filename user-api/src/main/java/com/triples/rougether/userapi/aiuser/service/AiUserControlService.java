package com.triples.rougether.userapi.aiuser.service;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class AiUserControlService {
    private final AiUserJobs jobs;
    private final AiUserObserver observer;
    private final AiUserActionExecutor executor;
    private final ObjectMapper mapper;
    private final AiUserMemoryProjection memoryProjection;
    private final AiUserSocialActivity social;
    private final jakarta.persistence.EntityManager entityManager;

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> snapshot(long id,String owner,long token) {
        return observer.snapshot(jobs.requireClaim(id,owner,token));
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public String execute(long id,String owner,long token,AiUserActionExecutor.Action action,JsonNode payload,String memory) {
        String previous=jobs.completedResult(id,owner,token);
        if (previous!=null) return previous;
        var job=jobs.requireClaim(id,owner,token);
        observer.source(job.userId(),job.sourceType(),job.sourceId());
        observer.requireContextAccessible(job);
        memoryProjection.requireMemorySources(job);
        observer.requireResponseTarget(job,action,payload);
        var rejected=social.reject(job,action,payload);
        if (rejected!=null) {
            action=AiUserActionExecutor.Action.WAIT;
            payload=mapper.createObjectNode().put("reason",rejected.name());
            memory="";
        }
        // 사전 관찰 엔티티를 버려 기존 서비스의 잠금 조회가 최신 room sequence/post 상태를 다시 읽게 함.
        entityManager.clear();
        Object result=executor.execute(job,action,payload);
        social.record(job,action,payload,result);
        String receipt=mapper.writeValueAsString(result);
        memoryProjection.recordAction(job,action.name(),payload);
        jobs.finish(job,receipt,memory);
        jobs.bringForwardSocialVisit(job,action);
        return receipt;
    }
}
