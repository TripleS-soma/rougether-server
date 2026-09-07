package com.triples.rougether.domain.billing.repository;
import com.triples.rougether.domain.billing.entity.FurnitureCreditPurchase;
import com.triples.rougether.domain.billing.entity.FurnitureCreditPurchase.*;
import java.time.Instant;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface FurnitureCreditPurchaseRepository extends JpaRepository<FurnitureCreditPurchase, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<FurnitureCreditPurchase> findByStoreAndEnvironmentAndReferenceHash(Store store, Environment environment, String hash);
    @Query("select p.userId from FurnitureCreditPurchase p where p.id = :id")
    Optional<Long> findOwnerId(@Param("id") String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from FurnitureCreditPurchase p where p.id = :id")
    Optional<FurnitureCreditPurchase> findForUpdate(@Param("id") String id);
    @Query("select p.id from FurnitureCreditPurchase p where p.store = 'GOOGLE' and p.storeConsumed = false "
            + "and p.nextConsumeAt <= :now order by p.nextConsumeAt")
    List<String> findDue(@Param("now") Instant now, Pageable page);
}
