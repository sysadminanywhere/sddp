package ru.sddp.platform.repository;

import ru.sddp.platform.domain.ServiceApi;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceApiRepository extends JpaRepository<ServiceApi, UUID> {
    List<ServiceApi> findByServiceIdOrderByNameAscVersionAsc(UUID serviceId);
    List<ServiceApi> findByServiceProjectIdOrderByServiceNameAscNameAscVersionAsc(UUID projectId);
    Optional<ServiceApi> findByIdAndServiceId(UUID id, UUID serviceId);
    Optional<ServiceApi> findByIdAndServiceProjectId(UUID id, UUID projectId);
    boolean existsByServiceIdAndNameAndVersion(UUID serviceId, String name, String version);
    boolean existsByServiceIdAndNameAndVersionAndIdNot(UUID serviceId, String name, String version, UUID id);
    long countByServiceId(UUID serviceId);
    void deleteByServiceId(UUID serviceId);
}
