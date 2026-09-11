package com.triples.rougether.domain.furniture.entity;
import jakarta.persistence.*;
import lombok.Getter;
@Entity @Table(name="furniture_worker_capacity") @Getter
public class FurnitureWorkerCapacity {
    @Id private Long id;
    @Column(nullable=false) private int maxInFlight;
    @Column(nullable=false) private boolean executionEnabled;
    @Column(nullable=false, length=16) private String executionMode = "RESIDENT";
    protected FurnitureWorkerCapacity() { }
}
