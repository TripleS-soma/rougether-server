package com.triples.rougether.domain.member.repository;

import com.triples.rougether.domain.member.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}
