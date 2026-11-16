package ${package_name};

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
<#if mapping_json?has_content>
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
</#if>

@Service
public class ${client_name} {
    private final RestClient restClient;
    <#if security_jwt>
    private final String bearerToken;
    </#if>
    private final CircuitBreaker breaker = CircuitBreaker.of("${client_name}", CircuitBreakerConfig.custom()
            .slidingWindowSize(5).minimumNumberOfCalls(5).failureRateThreshold(100)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .recordException(e -> e instanceof ResourceAccessException)
            .build());
    <#if mapping_json?has_content>
    private final ObjectMapper mapper;
    private final JsonNode transformations;
    </#if>
    <#if mapping_json?has_content>
    <#if security_jwt>
    public ${client_name}(RestClient.Builder builder, ObjectMapper mapper, String bearerToken) {
    <#else>
    public ${client_name}(RestClient.Builder builder, ObjectMapper mapper) {
    </#if>
        this.mapper = mapper;
        try {
            this.transformations = mapper.readTree("${mapping_json?j_string}").path("transformations");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Invalid generated mapping", e);
        }
    <#else>
    <#if security_jwt>
    public ${client_name}(RestClient.Builder builder, String bearerToken) {
    <#else>
    public ${client_name}(RestClient.Builder builder) {
    </#if>
    </#if>
    <#if security_jwt>
        this.bearerToken = java.util.Objects.requireNonNull(bearerToken, "bearerToken");
    </#if>
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(${timeout_ms?c});
        requestFactory.setReadTimeout(${timeout_ms?c});
        this.restClient = builder.requestFactory(requestFactory).baseUrl("${base_url}").build();
    }
    <#list methods as method_name>
    public Object ${method_name}(Object request) {
        Object payload = <#if mapping_json?has_content>transform(request)<#else>request</#if>;
        return breaker.executeSupplier(() -> executeRequest(payload));
    }
    </#list>
    private Object executeRequest(Object payload) {
        for (int attempt = 0;; attempt++) {
            try {
                return restClient.post()<#if security_jwt>.headers(headers -> headers.setBearerAuth(bearerToken))</#if>.uri("${target_endpoint}").body(payload).retrieve().body(Object.class);
            } catch (ResourceAccessException e) {
                if (attempt >= ${retries?c}) throw e;
            }
        }
    }
    <#if mapping_json?has_content>
    private ObjectNode transform(Object request) {
        JsonNode source = mapper.valueToTree(request);
        ObjectNode result = mapper.createObjectNode();
        for (JsonNode rule : transformations) {
            JsonNode value;
            switch (rule.path("type").asText()) {
                case "DIRECT_COPY":
                    value = read(source, rule.path("from").asText());
                    break;
                case "HARDCODED_VALUE":
                    value = rule.get("value");
                    break;
                case "CONCATENATE":
                    List<String> parts = new ArrayList<>();
                    for (JsonNode path : rule.path("from")) parts.add(read(source, path.asText()).asText());
                    value = mapper.getNodeFactory().textNode(String.join(rule.path("separator").asText(), parts));
                    break;
                default: throw new IllegalStateException("Unsupported mapping rule");
            }
            String[] fields = rule.path("to").asText().substring(2).split("\\.");
            ObjectNode parent = result;
            for (int i = 0; i < fields.length - 1; i++) {
                JsonNode existing = parent.get(fields[i]);
                if (existing == null) existing = parent.putObject(fields[i]);
                if (!(existing instanceof ObjectNode)) throw new IllegalArgumentException("Mapping destination conflicts with another rule");
                parent = (ObjectNode) existing;
            }
            parent.set(fields[fields.length - 1], value.deepCopy());
        }
        return result;
    }
    private JsonNode read(JsonNode source, String path) {
        JsonNode value = source;
        for (String field : path.substring(2).split("\\.")) value = value.path(field);
        if (value.isMissingNode()) throw new IllegalArgumentException("Missing mapping source: " + path);
        return value;
    }
    </#if>
}
