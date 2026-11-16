package ru.sddp.platform.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.sddp.platform.repository.AuditEventRepository;

@Service
public class AuditRetentionService {
    private final AuditEventRepository events;
    private final int retentionDays;

    public AuditRetentionService(AuditEventRepository events,
                                 @Value("${sddp.audit.retention-days:90}") int retentionDays) {
        if (retentionDays < 1) throw new IllegalArgumentException("sddp.audit.retention-days must be positive");
        this.events = events;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void deleteExpiredEvents() {
        events.deleteByCreatedAtBefore(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
    }
}
