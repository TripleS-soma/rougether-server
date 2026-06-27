package com.triples.rougether.domain.support;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import org.springframework.data.annotation.LastModifiedDate;

// createdAt + updatedAt 둘 다 갖는 엔티티용.
@Getter
@MappedSuperclass
public abstract class BaseEntity extends BaseCreatedEntity {

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
