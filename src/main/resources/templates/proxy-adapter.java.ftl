package ${package_name};

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
<#if http??>
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
<#else>
import org.springframework.http.client.SimpleClientHttpRequestFactory;
</#if>
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
<#if http??>
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.web.util.UriComponentsBuilder;
</#if>
<#if http?? && (http.structuredQuery || http.formBody)>
import java.util.function.BiConsumer;
</#if>
<#if http?? && http.structuredQuery && !mapping_json?has_content>
import java.util.ArrayList;
</#if>
<#if http?? && (http.structuredQuery || http.formBody) && !mapping_json?has_content>
import java.util.List;
</#if>
<#if http?? && http.formBody>
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
</#if>
<#if mapping_json?has_content || http??>
import org.springframework.http.MediaType;
</#if>
<#if mapping_json?has_content>
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
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
        <#if http??>
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(${timeout_ms?c})).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        <#else>
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(${timeout_ms?c});
        </#if>
        requestFactory.setReadTimeout(${timeout_ms?c});
        this.restClient = builder.requestFactory(requestFactory).baseUrl("${base_url}").build();
    }
    <#if http??>
    public Object ${methods[0]}(<#if http.pathParameters?has_content>Map<String, ?> pathParameters</#if><#if http.queryParameters?has_content><#if http.pathParameters?has_content>, </#if>Map<String, ?> queryParameters</#if><#if http.hasBody><#if http.pathParameters?has_content || http.queryParameters?has_content>, </#if>Object request</#if>) {
        var uri = UriComponentsBuilder.fromUriString("${base_url?j_string}").path("${target_endpoint?j_string}");
        Map<String, Object> values = new LinkedHashMap<>();
        <#list http.pathParameters as parameter>
        values.put("${parameter.name?j_string}", parameter(pathParameters, "${parameter.name?j_string}", true, true));
        </#list>
        <#list http.queryParameters as parameter>
        <#if http.structuredQuery>
        addQuery(uri, values, queryParameters, "${parameter.name?j_string}", ${parameter.required?c}, "${parameter.encoding}");
        <#else>
        Object query${parameter?index} = parameter(queryParameters, "${parameter.name?j_string}", ${parameter.required?c}, false);
        if (query${parameter?index} != null) {
            uri.queryParam("${parameter.name?j_string}", "{$query${parameter?index}}");
            values.put("$query${parameter?index}", query${parameter?index});
        }
        </#if>
        </#list>
        URI endpoint = uri.encode().buildAndExpand(values).toUri();
        <#if http.hasBody>
        <#if http.bodyRequired>
        java.util.Objects.requireNonNull(request, "request body");
        </#if>
        <#if http.formBody>
        Object payload = request == null ? null : form(<#if mapping_json?has_content>mapper.convertValue(transform(request), Map.class)<#else>request</#if>);
        <#else>
        Object payload = <#if mapping_json?has_content>request == null ? null : transform(request).toString().getBytes(StandardCharsets.UTF_8)<#else>request</#if>;
        </#if>
        <#else>
        Object payload = null;
        </#if>
        return breaker.executeSupplier(() -> executeRequest(endpoint, payload));
    }

    <#if http.pathParameters?has_content || (http.queryParameters?has_content && !http.structuredQuery)>
    private Object parameter(Map<String, ?> arguments, String name, boolean required, boolean path) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null && !required) return null;
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
            throw new IllegalArgumentException("Missing or invalid parameter: " + name);
        }
        if (path && (value.toString().isEmpty() || value.equals(".") || value.equals(".."))) {
            throw new IllegalArgumentException("Invalid path parameter: " + name);
        }
        return value;
    }
    </#if>

    <#if http.structuredQuery>
    private void addQuery(UriComponentsBuilder uri, Map<String, Object> values, Map<String, ?> arguments,
                          String name, boolean required, String encoding) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null) {
            if (required) throw new IllegalArgumentException("Missing parameter: " + name);
            return;
        }
        BiConsumer<String, Object> emit = (key, part) -> uri.queryParam(queryToken(values, key), queryToken(values, scalar(part)));
        switch (encoding) {
            case "SCALAR" -> emit.accept(name, value);
            case "DEEP_OBJECT" -> bracketValues(name, object(value), emit, 0);
            case "DEEP_OBJECT_OR_SCALAR" -> bracketValues(name, value, emit, 0);
            case "BRACKET_ARRAY" -> bracketValues(name, array(value), emit, 0);
            case "ARRAY" -> array(value).forEach(item -> emit.accept(name, item));
            case "OBJECT" -> object(value).forEach((key, item) -> emit.accept(field(key), item));
            case "ARRAY_CSV", "OBJECT_CSV" -> {
                var tokens = new ArrayList<String>();
                if (encoding.equals("ARRAY_CSV")) {
                    array(value).forEach(item -> tokens.add(queryToken(values, scalar(item))));
                } else {
                    object(value).forEach((key, item) -> {
                        tokens.add(queryToken(values, field(key)));
                        tokens.add(queryToken(values, scalar(item)));
                    });
                }
                if (!tokens.isEmpty()) uri.queryParam(queryToken(values, name), String.join(",", tokens));
            }
            default -> throw new IllegalArgumentException("Unsupported query encoding for " + name);
        }
    }

    private String queryToken(Map<String, Object> values, String value) {
        String variable = "$query" + values.size();
        values.put(variable, value);
        return "{" + variable + "}";
    }
    </#if>

    <#if http.formBody>
    private MultiValueMap<String, String> form(Object request) {
        var fields = new LinkedMultiValueMap<String, String>();
        var bracketFields = List.of(<#list http.bracketFormFields as field>"${field?j_string}"<#sep>, </#sep></#list>);
        BiConsumer<String, Object> emit = (key, value) -> fields.add(key, scalar(value));
        object(request).forEach((key, value) -> {
            String name = field(key);
            if (value == null) return;
            if (bracketFields.contains(name)) {
                bracketValues(name, value, emit, 0);
            } else if (value instanceof Map<?, ?> map) {
                map.forEach((child, item) -> emit.accept(field(child), item));
            } else if (value instanceof List<?> list) {
                list.forEach(item -> emit.accept(name, item));
            } else {
                emit.accept(name, value);
            }
        });
        return fields;
    }
    </#if>

    <#if http.structuredQuery || http.formBody>
    private void bracketValues(String name, Object value, BiConsumer<String, Object> emit, int depth) {
        if (depth > 16) throw new IllegalArgumentException("Parameter nesting limit exceeded for " + name);
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> bracketValues(name + "[" + field(key) + "]", item, emit, depth + 1));
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                String index = item instanceof Map<?, ?> || item instanceof List<?> ? Integer.toString(i) : "";
                bracketValues(name + "[" + index + "]", item, emit, depth + 1);
            }
        } else {
            emit.accept(name, value);
        }
    }

    private String scalar(Object value) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value.toString();
        throw new IllegalArgumentException("Expected a string, number or boolean query or form value");
    }

    private String field(Object key) {
        if (key instanceof String text && !text.isEmpty()) return text;
        throw new IllegalArgumentException("Expected a non-empty string key for query or form value");
    }

    private Map<?, ?> object(Object value) {
        if (value instanceof Map<?, ?> map) return map;
        throw new IllegalArgumentException("Expected a map for object query or form value");
    }
    </#if>
    <#if http.structuredQuery>
    private List<?> array(Object value) {
        if (value instanceof List<?> list) return list;
        throw new IllegalArgumentException("Expected a list for array query value");
    }
    </#if>

    private Object executeRequest(URI endpoint, Object payload) {
        for (int attempt = 0;; attempt++) {
            try {
                var request = restClient.method(HttpMethod.${http.method}).uri(endpoint);
                <#if security_jwt>
                request.headers(headers -> headers.setBearerAuth(bearerToken));
                </#if>
                if (payload != null) request.contentType(MediaType.<#if http.formBody>APPLICATION_FORM_URLENCODED<#else>APPLICATION_JSON</#if>).body(payload);
                return request.retrieve().body(Object.class);
            } catch (ResourceAccessException e) {
                if (attempt >= ${retries?c}) throw e;
            }
        }
    }
    <#else>
    <#list methods as method_name>
    public Object ${method_name}(Object request) {
        <#if mapping_json?has_content>
        byte[] payload = transform(request).toString().getBytes(StandardCharsets.UTF_8);
        <#else>
        Object payload = request;
        </#if>
        return breaker.executeSupplier(() -> executeRequest(payload));
    }
    </#list>
    private Object executeRequest(Object payload) {
        for (int attempt = 0;; attempt++) {
            try {
                return restClient.post()<#if security_jwt>.headers(headers -> headers.setBearerAuth(bearerToken))</#if>.uri("${target_endpoint}")
                    <#if mapping_json?has_content>
                    .contentType(MediaType.APPLICATION_JSON)
                    </#if>
                    .body(payload).retrieve().body(Object.class);
            } catch (ResourceAccessException e) {
                if (attempt >= ${retries?c}) throw e;
            }
        }
    }
    </#if>
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
