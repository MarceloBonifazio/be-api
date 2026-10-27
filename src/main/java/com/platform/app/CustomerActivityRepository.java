package com.platform.app;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerActivityRepository extends JpaRepository<CustomerActivity, Long> {
  Page<CustomerActivity> findByCustomerId(Long customerId, Pageable pageable);
}
