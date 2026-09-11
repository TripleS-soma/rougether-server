package com.triples.rougether.domain.furniture.repository;
import com.triples.rougether.domain.furniture.entity.FurnitureWorkerCapacity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
public interface FurnitureWorkerCapacityRepository extends JpaRepository<FurnitureWorkerCapacity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from FurnitureWorkerCapacity c where c.id=1")
    Optional<FurnitureWorkerCapacity> lock();
}
