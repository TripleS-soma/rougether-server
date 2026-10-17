package com.triples.rougether.userapi.aiuser.web;

import com.triples.rougether.userapi.aiuser.config.AiUserProperties;
import com.triples.rougether.userapi.aiuser.service.AiUserJobs;
import com.triples.rougether.userapi.aiuser.service.AiUserSeedService;
import com.triples.rougether.userapi.aiuser.service.AiUserControlService;
import com.triples.rougether.userapi.aiuser.service.AiUserActionExecutor;
import com.triples.rougether.userapi.aiuser.service.AiUserMemoryProjection;
import com.triples.rougether.common.error.ErrorResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

// 전용 service secret을 요구함. 공개 reverse proxy는 /internal/ai-users를 라우팅하지 않아야 함.
@RestController
@io.swagger.v3.oas.annotations.Hidden
@RequestMapping("/internal/ai-users")
@RequiredArgsConstructor
@ConditionalOnProperty(name="ai-users.enabled",havingValue="true")
public class AiUserController {
    private final AiUserProperties properties;
    private final AiUserSeedService seed;
    private final AiUserJobs jobs;
    private final AiUserControlService control;
    private final AiUserMemoryProjection memory;

    public record Claim(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,80}") String owner) {}
    public record Lease(@NotBlank String owner,@Positive long token) {}
    public record Execution(@NotBlank String owner,@Positive long token,
            @NotNull AiUserActionExecutor.Action action,@NotNull JsonNode payload,@Size(max=1000) String memory) {}
    public record MemoryContext(@NotBlank String owner,@Positive long token,
            @NotNull @Size(max=8) List<AiUserMemoryProjection.Fact> facts) {}
    public record MemoryBatch(@Min(1) @Max(10) int limit) {}
    public record MemoryAck(boolean success,boolean revoked) {}
    public record MemoryCheck(@Positive long aiUserId,@NotNull @Size(max=64) List<AiUserMemoryProjection.MemoryRecord> records) {}

    @PostMapping("/memory/check")
    public List<String> memoryCheck(@RequestHeader(value="Authorization",required=false) String auth,
            @Valid @RequestBody MemoryCheck request) {
        authorize(auth);
        if (request.records().stream().anyMatch(r -> r==null || r.episode()==null || !r.episode().matches("[a-f0-9-]{36}")
                || r.sources()==null || r.sources().size()>64)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return memory.invalidEpisodes(request.aiUserId(),request.records());
    }

    @PostMapping("/jobs/{id}/memory-context")
    public List<AiUserMemoryProjection.Fact> memoryContext(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable long id,@Valid @RequestBody MemoryContext request) {
        authorize(auth);
        if (request.facts().stream().anyMatch(f -> f==null || f.text()==null || f.text().length()>2000
                || f.sources()==null || f.sources().size()>64 || f.episodes()==null || f.episodes().size()>32))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return memory.authorize(id,request.owner(),request.token(),request.facts());
    }
    @PostMapping("/memory/pending")
    public List<Map<String,Object>> memoryPending(@RequestHeader(value="Authorization",required=false) String auth,
            @Valid @RequestBody MemoryBatch request) { authorize(auth); return memory.pending(request.limit()); }
    @PostMapping("/memory/{id}/ack")
    public void memoryAck(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable long id,@RequestBody MemoryAck request) { authorize(auth); memory.acknowledge(id,request.success(),request.revoked()); }

    @PostMapping("/seed")
    public List<Long> seed(@RequestHeader(value="Authorization",required=false) String auth) { authorize(auth); return seed.seed(); }
    @PostMapping("/enabled")
    public void enable(@RequestHeader(value="Authorization",required=false) String auth,@RequestParam boolean enabled) {
        authorize(auth); jobs.setEnabled(enabled);
    }
    @PostMapping("/claim")
    public Map<String,Object> claim(@RequestHeader(value="Authorization",required=false) String auth,@Valid @RequestBody Claim request) {
        authorize(auth);
        var job=jobs.claim(request.owner());
        return job==null?Map.of():Map.of("job",job);
    }
    @PostMapping("/jobs/{id}/snapshot")
    public Map<String,Object> snapshot(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable long id,@Valid @RequestBody Lease lease) {
        authorize(auth); return control.snapshot(id,lease.owner(),lease.token());
    }
    @PostMapping("/jobs/{id}/execute")
    public Map<String,String> execute(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable long id,@Valid @RequestBody Execution request) {
        authorize(auth);
        return Map.of("result",control.execute(id,request.owner(),request.token(),request.action(),request.payload(),request.memory()));
    }
    @PostMapping("/jobs/{id}/fail")
    public void fail(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable long id,@Valid @RequestBody Lease lease) { authorize(auth); jobs.fail(id,lease.owner(),lease.token()); }
    @PostMapping("/jobs/{id}/defer")
    public void defer(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable long id,@Valid @RequestBody Lease lease) { authorize(auth); jobs.defer(id,lease.owner(),lease.token()); }
    private void authorize(String auth) {
        String expected="Bearer "+properties.controlToken();
        if (auth==null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),auth.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<ErrorResponse> handleControlError(ResponseStatusException error) {
        return org.springframework.http.ResponseEntity.status(error.getStatusCode())
                .body(ErrorResponse.of("AI_CONTROL_REJECTED","AI 제어 요청을 처리할 수 없습니다."));
    }
}
