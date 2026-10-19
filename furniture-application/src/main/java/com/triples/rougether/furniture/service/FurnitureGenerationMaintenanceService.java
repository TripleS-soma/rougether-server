package com.triples.rougether.furniture.service;

import com.triples.rougether.infra.assets.AssetStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 만료 정산 후 임시 사진을 정리함. S3 호출은 DB 트랜잭션 밖에서 수행함. */
@Service
@RequiredArgsConstructor
@Slf4j
public class FurnitureGenerationMaintenanceService {
    private final FurnitureGenerationTransactions transactions;
    private final AssetStorageService storage;

    public void maintain() {
        for (String id : transactions.maintenanceCandidates()) {
            try {
                var cleanup = transactions.maintain(id);
                if (cleanup == null) continue;
                // 성공한 공개 결과물은 보관함/방이 참조하므로 삭제하지 않음.
                if (cleanup.sourceKey() != null) storage.delete(cleanup.sourceKey());
                if (cleanup.rawSourceKey() != null) storage.delete(cleanup.rawSourceKey());
                if (cleanup.candidateKey() != null && cleanup.candidateKey().startsWith("private/furniture-generation/")) {
                    storage.delete(cleanup.candidateKey());
                }
                transactions.cleaned(cleanup);
            } catch (RuntimeException e) { log.warn("가구 임시 사진 정리 보류 job={}", id); }
        }
    }
}
