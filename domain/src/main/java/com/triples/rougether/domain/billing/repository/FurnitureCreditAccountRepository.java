package com.triples.rougether.domain.billing.repository;

import com.triples.rougether.domain.billing.entity.FurnitureCreditAccount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FurnitureCreditAccountRepository extends JpaRepository<FurnitureCreditAccount, Long> {
    @Query("select a.userId from FurnitureCreditAccount a where a.accountToken = :token")
    Optional<Long> findUserIdByAccountToken(@Param("token") String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from FurnitureCreditAccount a where a.userId = :id")
    Optional<FurnitureCreditAccount> findForUpdate(@Param("id") Long id);

    @Modifying
    @Query(value = """
            INSERT INTO furniture_credit_accounts (user_id, account_token, balance, reserved)
            VALUES (:id, :token, 0, 0)
            ON DUPLICATE KEY UPDATE user_id = user_id
            """, nativeQuery = true)
    void createIfAbsent(@Param("id") Long id, @Param("token") String token);

    // 호출자가 사용자 행을 잠근 상태에서 실행한다. 없는 계좌를 FOR UPDATE로 먼저 찾으면
    // 서로 다른 회원도 같은 인덱스 구간을 잠근 채 삽입을 기다릴 수 있다.
    default FurnitureCreditAccount lockOrCreate(Long userId) {
        createIfAbsent(userId, UUID.randomUUID().toString());
        return findForUpdate(userId).orElseThrow();
    }
}
