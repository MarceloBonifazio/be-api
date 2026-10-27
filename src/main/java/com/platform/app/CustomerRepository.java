package com.platform.app;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.time.Instant;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
  Page<Customer> findByStatus(CustomerStatus status, Pageable pageable);

  Page<Customer> findByNameContainingIgnoreCase(String name, Pageable pageable);

  @Query("""
      select c from Customer c where (:status is null or c.status = :status)
      and (lower(c.name) like :term escape '!' or lower(c.email) like :term escape '!'
      or c.cpf like :term escape '!' or (:cpfTerm <> '' and c.cpf like :cpfTerm escape '!'))
      """)
  Page<Customer> search(String term, String cpfTerm, CustomerStatus status, Pageable pageable);

  long countByStatus(CustomerStatus status);

  long countByScoreIsNotNull();

  long countByCreatedAtGreaterThanEqual(Instant since);

  @Query("select avg(c.score) from Customer c where c.score is not null")
  Double averageScore();

  boolean existsByCpf(String cpf);

  boolean existsByCpfAndIdNot(String cpf, Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Customer c where c.id = :id")
  Optional<Customer> findLockedById(Long id);
}
