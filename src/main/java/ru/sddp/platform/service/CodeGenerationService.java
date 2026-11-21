package ru.sddp.platform.service;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import ru.sddp.platform.api.*;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class CodeGenerationService {
    private final IntermediateRepresentationBuilder ir; private final PluginExecutionFactory pluginFactory; private final BuildEventPublisher events; private final ru.sddp.platform.repository.CodegenPluginRepository plugins;
    private final Configuration freemarker; private final MeterRegistry meters;
    public CodeGenerationService(IntermediateRepresentationBuilder ir, Configuration freemarker, PluginExecutionFactory pluginFactory, BuildEventPublisher events, ru.sddp.platform.repository.CodegenPluginRepository plugins, MeterRegistry meters) { this.ir = ir; this.freemarker = freemarker; this.pluginFactory = pluginFactory; this.events = events; this.plugins = plugins; this.meters = meters; }
    public CodegenModels.GenerateResponse generate(CodegenModels.GenerateRequest request) {
        if (request.projectId() == null) throw new IllegalArgumentException("projectId is required");
        ApiModels.IrManifest manifest = ir.build(request.projectId(), request.environment());
        events.publish("CODEGEN_STARTED", manifest);
        if (request.plugin() != null) {
            boolean registered = plugins.findByActiveTrue().stream().anyMatch(p -> p.getLanguage().equals(request.plugin().language()) && p.getDeliveryType().equals(request.plugin().deliveryType()) && p.getExecutableTarget().equals(request.plugin().executableTarget()));
            if (!registered) throw new IllegalArgumentException("Plugin is not registered and active");
            PluginExecutor executor = pluginFactory.resolve(request.plugin());
            var result = executePlugin(executor, manifest, request.plugin());
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
            if (!"NONE".equals(security) && !"JWT".equals(security)) throw new IllegalArgumentException("Built-in REST generator does not support " + security + "; use a registered plugin");
            int timeout = settings.path("timeout_ms").asInt(5000);
            int retries = settings.path("retries").asInt(3);
            if (timeout < 1 || retries < 0 || retries > 10) throw new IllegalArgumentException("timeout_ms must be positive and retries must be between 0 and 10");
            String scheme = settings.path("scheme").asText("http").toLowerCase(java.util.Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) throw new IllegalArgumentException("scheme must be http or https");
            if ("JWT".equals(security) && !"https".equals(scheme)) throw new IllegalArgumentException("JWT clients require HTTPS");
            String host = settings.path("host").asText();
            String endpoint = settings.path("target_endpoint").asText();
            if (!host.matches("[A-Za-z0-9.:-]+") || settings.path("port").asInt() < 1 || settings.path("port").asInt() > 65535) {
                throw new IllegalArgumentException("Invalid REST host or port");
            }
            HttpOperation http = connection.methods().getFirst().http_operation();
            if (http == null && !endpoint.matches("/[A-Za-z0-9_./~%-]*")) {
                throw new IllegalArgumentException("Invalid REST endpoint");
            }
            if (http != null) {
                endpoint = "/".equals(endpoint) ? http.path() : endpoint;
                if (!HttpOperation.placeholders(endpoint).equals(HttpOperation.placeholders(http.path()))) {
                    throw new IllegalArgumentException("REST endpoint parameters do not match the imported contract");
                }
            }
            String mapping = connection.mapping_rules() == null || connection.mapping_rules().isNull() ? "" : connection.mapping_rules().toString();
            var model = new LinkedHashMap<String, Object>(Map.of("package_name", request.packageName(),
                "client_name", className, "base_url", scheme + "://" + host + ":" + settings.path("port").asInt(),
                "target_endpoint", endpoint, "methods", methods, "mapping_json", mapping,
                "timeout_ms", timeout, "retries", retries, "security_jwt", "JWT".equals(security)));
            if (http != null) model.put("http", httpTemplateModel(http));
            template.process(model, writer);
            files.put(className + ".java", writer.toString());
            }
            var first = files.entrySet().iterator().next();
            CodegenModels.GenerateResponse result = new CodegenModels.GenerateResponse("JAVA", first.getKey(), first.getValue(), files);
            events.publish("CODEGEN_COMPLETED", manifest);
            return result;
        } catch (java.io.IOException | TemplateException e) { throw new IllegalStateException("Code generation failed", e); }
    }

    private Map<String, Object> httpTemplateModel(HttpOperation http) {
        var path = http.parameters().stream().filter(parameter -> "path".equals(parameter.in()))
            .map(parameter -> Map.of("name", parameter.name(), "required", parameter.required())).toList();
        var query = http.parameters().stream().filter(parameter -> "query".equals(parameter.in()))
            .map(parameter -> Map.of("name", parameter.name(), "required", parameter.required())).toList();
        return Map.of("method", http.method(), "hasBody", http.requestBody(), "bodyRequired", http.bodyRequired(),
            "pathParameters", path, "queryParameters", query);
    }

    private CodegenModels.PluginExecutionResult executePlugin(PluginExecutor executor, ApiModels.IrManifest manifest,
                                                               CodegenModels.PluginDescriptor plugin) {
        Timer.Sample sample = Timer.start(meters);
        try {
            var result = executor.execute(manifest, plugin);
            meters.counter("sddp.codegen.plugin.executions", "language", plugin.language(), "status", "success").increment();
            return result;
        } catch (RuntimeException ex) {
            meters.counter("sddp.codegen.plugin.executions", "language", plugin.language(), "status", "failure").increment();
            throw ex;
        } finally {
            sample.stop(meters.timer("sddp.codegen.plugin.duration", "language", plugin.language()));
        }
    }
}
