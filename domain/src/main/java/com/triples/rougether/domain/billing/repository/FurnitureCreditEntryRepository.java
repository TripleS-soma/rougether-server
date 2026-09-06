package com.triples.rougether.domain.billing.repository;
import com.triples.rougether.domain.billing.entity.FurnitureCreditEntry;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FurnitureCreditEntryRepository extends JpaRepository<FurnitureCreditEntry, Long> {
    List<FurnitureCreditEntry> findByUserIdOrderByIdDesc(Long userId, Pageable page);
}
