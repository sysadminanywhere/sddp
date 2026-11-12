package ru.sddp.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import java.util.*;

/** Enforces the two contract-breaking changes defined by the SDDP plan. */
@Service
public class BackwardCompatibilityEngine {
    public void assertCompatible(JsonNode previousResponse, JsonNode nextResponse, JsonNode previousRequest, JsonNode nextRequest) {
        removedProperties(previousResponse, nextResponse, "response");
        addedRequiredProperties(previousRequest, nextRequest);
    }
    private void removedProperties(JsonNode previous, JsonNode next, String direction) {
        Set<String> oldNames = propertyNames(previous); Set<String> newNames = propertyNames(next);
        Set<String> removed = new HashSet<>(oldNames); removed.removeAll(newNames);
        if (!removed.isEmpty()) throw new CompatibilityViolationException("Breaking change: removed " + direction + " fields: " + removed);
        for (String name : oldNames) removedProperties(previous.path("properties").path(name), next.path("properties").path(name), direction + "." + name);
        if (previous != null && next != null && previous.path("items").isObject()) removedProperties(previous.path("items"), next.path("items"), direction + "[]");
    }
    private void addedRequiredProperties(JsonNode previous, JsonNode next) {
        Set<String> oldRequired = required(previous); Set<String> newRequired = required(next);
        newRequired.removeAll(oldRequired);
        if (!newRequired.isEmpty()) throw new CompatibilityViolationException("Breaking change: new required request fields: " + newRequired);
        Set<String> common = propertyNames(previous); common.retainAll(propertyNames(next));
        for (String name : common) addedRequiredProperties(previous.path("properties").path(name), next.path("properties").path(name));
        if (previous != null && next != null && previous.path("items").isObject() && next.path("items").isObject()) addedRequiredProperties(previous.path("items"), next.path("items"));
    }
    private Set<String> propertyNames(JsonNode schema) { Set<String> result = new HashSet<>(); if (schema != null && schema.path("properties").isObject()) schema.path("properties").fieldNames().forEachRemaining(result::add); return result; }
    private Set<String> required(JsonNode schema) { Set<String> result = new HashSet<>(); if (schema != null && schema.path("required").isArray()) schema.path("required").forEach(n -> result.add(n.asText())); return result; }
}
