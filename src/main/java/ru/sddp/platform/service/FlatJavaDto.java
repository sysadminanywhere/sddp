package ru.sddp.platform.service;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.SourceVersion;
import tools.jackson.databind.JsonNode;

final class FlatJavaDto {
    private static final Set<String> RESERVED = Set.of("clone", "finalize", "getClass", "hashCode", "notify",
        "notifyAll", "toString", "wait");
    private static final Map<String, String> TYPES = Map.of("string", "String", "boolean", "Boolean",
        "integer", "java.math.BigInteger", "number", "java.math.BigDecimal");

    static Optional<String> source(String packageName, String className, JsonNode schema) {
        if (!"object".equals(schema.path("type").asText()) || schema.has("allOf") || schema.has("oneOf")
            || schema.has("anyOf") || !schema.path("properties").isObject() || schema.path("properties").isEmpty()) {
            return Optional.empty();
        }
        var components = new ArrayList<String>();
        for (var property : schema.path("properties").properties().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            String name = property.getKey();
            String type = javaType(property.getValue());
            if (type == null || !name.matches("[A-Za-z_$][A-Za-z0-9_$]*") || SourceVersion.isKeyword(name) || RESERVED.contains(name)) {
                return Optional.empty();
            }
            components.add(type + " " + name);
        }
        return Optional.of("""
            package %s;

            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
            public record %s(%s) { }
            """.formatted(packageName, className, String.join(", ", components)));
    }

    private static String javaType(JsonNode schema) {
        String type = schema.path("type").asText("");
        if ("array".equals(type)) {
            String item = TYPES.get(schema.path("items").path("type").asText(""));
            return item == null ? null : "java.util.List<" + item + ">";
        }
        return TYPES.get(type);
    }
}
