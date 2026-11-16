package ru.sddp.platform.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sddp.platform.domain.AuditEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
    @Modifying
    @Transactional
    long deleteByCreatedAtBefore(Instant cutoff);
}
