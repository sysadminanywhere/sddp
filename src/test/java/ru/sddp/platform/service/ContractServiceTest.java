package ru.sddp.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.sddp.platform.api.ContractUpdateRequest;
import ru.sddp.platform.domain.Contract;
import ru.sddp.platform.domain.Project;
import ru.sddp.platform.repository.ContractRepository;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContractServiceTest {
    @Test void newVersionPreservesOriginalContract() throws Exception {
        var mapper = new ObjectMapper(); var oldSchema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}");
        var nextSchema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"note\":{\"type\":\"string\"}}}");
        var original = new Contract(new Project("test"), "order", "1", oldSchema, oldSchema);
        UUID projectId=UUID.randomUUID(), contractId=UUID.randomUUID(); var repository=mock(ContractRepository.class);
        when(repository.findByIdAndProjectId(contractId, projectId)).thenReturn(Optional.of(original));
        when(repository.save(any(Contract.class))).thenAnswer(call -> call.getArgument(0));
        var service=new ContractService(repository, new BackwardCompatibilityEngine(), new ContractSchemaValidator());
        var updated=service.update(projectId,contractId,new ContractUpdateRequest("2", nextSchema,nextSchema));
        assertNotSame(original, updated); assertEquals("1", original.getVersion()); assertEquals(oldSchema, original.getRequestSchema());
        assertEquals("2", updated.getVersion()); assertEquals(nextSchema, updated.getRequestSchema());
    }
}
