package com.platform.app;

import java.util.List;
import org.springframework.data.jpa.repository.*;

public interface OutboxRepository extends JpaRepository<OutboxEvent, java.util.UUID> {
  @Query(
      value =
          "SELECT * FROM outbox_events WHERE published_at IS NULL ORDER BY created_at LIMIT 50 FOR"
              + " UPDATE SKIP LOCKED",
      nativeQuery = true)
  List<OutboxEvent> lockPending();
}
