package ru.sddp.platform.service;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import ru.sddp.platform.repository.AuditEventRepository;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditRetentionServiceTest {
    @Test
    void deletesEventsOlderThanConfiguredRetention() {
        var repository = mock(AuditEventRepository.class);
        var service = new AuditRetentionService(repository, 30);

        service.deleteExpiredEvents();

        verify(repository).deleteByCreatedAtBefore(any(Instant.class));
    }

    @Test
    void rejectsNonPositiveRetention() {
        assertThrows(IllegalArgumentException.class, () -> new AuditRetentionService(mock(AuditEventRepository.class), 0));
    }
}
