package com.triples.rougether.domain.billing.repository;
import com.triples.rougether.domain.billing.entity.FurnitureCreditAccount;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface FurnitureCreditAccountRepository extends JpaRepository<FurnitureCreditAccount, Long> {
    @Query("select a.userId from FurnitureCreditAccount a where a.accountToken = :token")
    Optional<Long> findUserIdByAccountToken(@Param("token") String token);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from FurnitureCreditAccount a where a.userId = :id")
    Optional<FurnitureCreditAccount> findForUpdate(@Param("id") Long id);
}
