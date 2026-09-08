package com.llmgateway.repository;

import com.llmgateway.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmail(String email);

    @Query("SELECT u FROM User u WHERE LOWER(u.email) = LOWER(:identifier) OR LOWER(u.email) LIKE LOWER(CONCAT(:identifier, '@%')) ORDER BY CASE WHEN LOWER(u.email) = LOWER(:identifier) THEN 0 ELSE 1 END, u.id ASC")
    List<User> findByIdentifierMatches(@Param("identifier") String identifier);
}
