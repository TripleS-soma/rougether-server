package com.triples.rougether.domain.house.repository;

import com.triples.rougether.domain.house.entity.House;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseRepository extends JpaRepository<House, Long> {

    Optional<House> findByInviteCode(String inviteCode);
}
