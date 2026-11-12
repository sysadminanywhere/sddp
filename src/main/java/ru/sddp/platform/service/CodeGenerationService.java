package ru.sddp.platform.service;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.springframework.stereotype.Service;
import ru.sddp.platform.api.*;
import java.io.StringWriter;
import java.util.Map;

@Service
public class CodeGenerationService {
    private final IntermediateRepresentationBuilder ir; private final PluginExecutionFactory pluginFactory; private final BuildEventPublisher events; private final ru.sddp.platform.repository.CodegenPluginRepository plugins;
    private final Configuration freemarker;
    public CodeGenerationService(IntermediateRepresentationBuilder ir, Configuration freemarker, PluginExecutionFactory pluginFactory, BuildEventPublisher events, ru.sddp.platform.repository.CodegenPluginRepository plugins) { this.ir = ir; this.freemarker = freemarker; this.pluginFactory = pluginFactory; this.events = events; this.plugins = plugins; }
    public CodegenModels.GenerateResponse generate(CodegenModels.GenerateRequest request) {
        if (request.projectId() == null) throw new IllegalArgumentException("projectId is required");
        ApiModels.IrManifest manifest = ir.build(request.projectId(), request.environment());
        events.publish("CODEGEN_STARTED", manifest);
        if (request.plugin() != null) {
            boolean registered = plugins.findByActiveTrue().stream().anyMatch(p -> p.getLanguage().equals(request.plugin().language()) && p.getDeliveryType().equals(request.plugin().deliveryType()) && p.getExecutableTarget().equals(request.plugin().executableTarget()));
            if (!registered) throw new IllegalArgumentException("Plugin is not registered and active");
            PluginExecutor executor = pluginFactory.resolve(request.plugin());
            var result = executor.execute(manifest, request.plugin());
            events.publish("CODEGEN_COMPLETED", manifest);
            return new CodegenModels.GenerateResponse(request.plugin().language(), null, null, result.files());
        }
        var connections = manifest.services().stream().flatMap(s -> s.outbound_connections().stream()).toList();
        if (connections.isEmpty()) throw new IllegalArgumentException("IR contains no outbound connections");
        if (!request.packageName().matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*") || !request.clientName().matches("[A-Za-z_$][A-Za-z0-9_$]*")) throw new IllegalArgumentException("Package and client names must be valid Java identifiers");
        try {
            Template template = freemarker.getTemplate("proxy-adapter.java.ftl");
            var files = new java.util.LinkedHashMap<String,String>();
            int index = 0;
            for (var connection : connections) {
            if (!"REST".equals(connection.transport_type())) throw new IllegalArgumentException("Built-in generator supports REST only; use a registered plugin for " + connection.transport_type());
            if (connection.methods().isEmpty()) throw new IllegalArgumentException("Connection has no methods");
            var methods = new java.util.ArrayList<String>();
            for (var method : connection.methods()) {
                if (!"REQUEST_RESPONSE".equals(method.pattern())) throw new IllegalArgumentException("Built-in REST generator supports REQUEST_RESPONSE only");
                String name = method.method_name().replaceAll("[^A-Za-z0-9_$]", "_");
                if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*") || javax.lang.model.SourceVersion.isKeyword(name) || methods.contains(name)) throw new IllegalArgumentException("Method name cannot be represented uniquely in Java: " + method.method_name());
                methods.add(name);
            }
            String className = connections.size() == 1 ? request.clientName() : request.clientName() + (++index);
            StringWriter writer = new StringWriter();
            var settings = connection.connection_settings();
            String security = settings.path("security_type").asText("NONE");
            if (!"NONE".equals(security)) throw new IllegalArgumentException("Built-in REST generator does not support " + security + "; use a registered plugin");
            int timeout = settings.path("timeout_ms").asInt(5000);
            int retries = settings.path("retries").asInt(3);
            if (timeout < 1 || retries < 0 || retries > 10) throw new IllegalArgumentException("timeout_ms must be positive and retries must be between 0 and 10");
            String host = settings.path("host").asText();
            String endpoint = settings.path("target_endpoint").asText();
            if (!host.matches("[A-Za-z0-9.:-]+") || !endpoint.matches("/[A-Za-z0-9_./~%-]*") || settings.path("port").asInt() < 1 || settings.path("port").asInt() > 65535) throw new IllegalArgumentException("Invalid REST host, port or endpoint");
            String mapping = connection.mapping_rules() == null || connection.mapping_rules().isNull() ? "" : connection.mapping_rules().toString();
            template.process(Map.of("package_name", request.packageName(), "client_name", className, "base_url", "http://" + host + ":" + settings.path("port").asInt(), "target_endpoint", endpoint, "methods", methods, "mapping_json", mapping, "timeout_ms", timeout, "retries", retries), writer);
            files.put(className + ".java", writer.toString());
            }
            var first = files.entrySet().iterator().next();
            CodegenModels.GenerateResponse result = new CodegenModels.GenerateResponse("JAVA", first.getKey(), first.getValue(), files);
            events.publish("CODEGEN_COMPLETED", manifest);
            return result;
        } catch (java.io.IOException | TemplateException e) { throw new IllegalStateException("Code generation failed", e); }
    }
}
