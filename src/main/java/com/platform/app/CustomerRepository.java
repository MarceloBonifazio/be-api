package com.platform.app;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
  Page<Customer> findByStatus(CustomerStatus status, Pageable pageable);

  Page<Customer> findByNameContainingIgnoreCase(String name, Pageable pageable);

  boolean existsByCpf(String cpf);

  boolean existsByCpfAndIdNot(String cpf, Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Customer c where c.id = :id")
  Optional<Customer> findLockedById(Long id);
}
