package ru.sddp.platform.service;

import tools.jackson.databind.ObjectMapper;
import freemarker.template.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.api.CodegenModels;
import ru.sddp.platform.repository.CodegenPluginRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeGenerationSafetyTest {
    @Test void refusesToGenerateInsecureClientForMtls() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var settings = mapper.readTree("{\"host\":\"legacy\",\"port\":443,\"target_endpoint\":\"/\",\"security_type\":\"MTLS\"}");
        var method = new ApiModels.IrMethod("send", "REQUEST_RESPONSE", mapper.createObjectNode(), mapper.createObjectNode());
        var connection = new ApiModels.IrConnection("client", "legacy", "REST", settings, List.of(method), null);
        var manifest = new ApiModels.IrManifest("system", "DEV", Instant.now(), List.of(new ApiModels.IrService("core", false, List.of(connection))));
        var builder = mock(IntermediateRepresentationBuilder.class);
        when(builder.build(any(), anyString())).thenReturn(manifest);
        var config = new Configuration(Configuration.VERSION_2_3_34);
        config.setClassLoaderForTemplateLoading(getClass().getClassLoader(), "templates");
        @SuppressWarnings("unchecked") ObjectProvider<PluginExecutor> executors = mock(ObjectProvider.class);
        var generator = new CodeGenerationService(builder, config, new PluginExecutionFactory(executors), mock(BuildEventPublisher.class), mock(CodegenPluginRepository.class));
        var request = new CodegenModels.GenerateRequest(UUID.randomUUID(), "DEV", "example", "Client", null);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> generator.generate(request)).getMessage().contains("MTLS"));
    }
}
