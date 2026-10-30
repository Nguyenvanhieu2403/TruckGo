package com.truckgo.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    Optional<User> findByPhone(String phone);
    boolean existsByEmail(String email);
    boolean existsByPhone(String phone);
    boolean existsByEmailAndIdNot(String email, UUID id);
    boolean existsByPhoneAndIdNot(String phone, UUID id);

    @Query("select u from User u where (:role is null or u.role = :role) and (:status is null or u.status = :status)")
    Page<User> search(@Param("role") UserRole role, @Param("status") UserStatus status, Pageable pageable);
}
