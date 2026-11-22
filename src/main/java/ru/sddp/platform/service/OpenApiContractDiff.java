package ru.sddp.platform.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import ru.sddp.platform.api.HttpOperation;
import ru.sddp.platform.api.OpenApiModels.Change;
import ru.sddp.platform.api.OpenApiModels.Comparison;
import ru.sddp.platform.domain.ServiceApi;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

final class OpenApiContractDiff {
    private static final int LIMIT = 50;
    private final List<Change> changes = new ArrayList<>();

    static Comparison compare(ServiceApi baseline, JsonNode request, JsonNode response, HttpOperation http, ObjectMapper mapper) {
        var diff = new OpenApiContractDiff();
        diff.visit("/requestSchema", baseline.getRequestSchema(), request);
        diff.visit("/responseSchema", baseline.getResponseSchema(), response);
        HttpOperation before = baseline.getHttpOperation();
        diff.visit("/http", httpTree(before, before, mapper), httpTree(http, before, mapper));
        if (diff.changes.isEmpty()) return null;
        return new Comparison(baseline.getVersion(), List.copyOf(diff.changes.subList(0, Math.min(LIMIT, diff.changes.size()))),
            diff.changes.size() > LIMIT);
    }

    private static ObjectNode httpTree(HttpOperation http, HttpOperation baseline, ObjectMapper mapper) {
        if (http == null) return null;
        var parameters = new HashMap<String, HttpOperation.Parameter>();
        if (baseline != null) {
            baseline.parameters().forEach(parameter -> parameters.put(parameter.in() + ":" + parameter.name(), parameter));
        }
        var tree = mapper.createObjectNode().put("method", http.method()).put("path", http.path())
            .put("requestBody", http.requestBody()).put("bodyRequired", http.bodyRequired());
        if (http.bodyContentType() != null) tree.put("bodyContentType", http.bodyContentType());
        // Older imported operations do not record the chosen status or parameter schema.
        if (baseline != null && baseline.responseStatus() != null) tree.put("responseStatus", http.responseStatus());
        var fields = tree.putArray("bracketFormFields");
        http.bracketFormFields().stream().sorted().forEach(fields::add);
        var entries = tree.putObject("parameters");
        http.parameters().forEach(parameter -> {
            String key = parameter.in() + ":" + parameter.name();
            var entry = entries.putObject(key).put("required", parameter.required()).put("encoding", parameter.encoding().name());
            var known = parameters.get(key);
            if (parameter.schema() != null && (known == null || known.schema() != null)) entry.set("schema", parameter.schema());
        });
        return tree;
    }

    private void visit(String path, JsonNode before, JsonNode after) {
        if (changes.size() > LIMIT || Objects.equals(before, after)) return;
        if (before == null || after == null) {
            changes.add(new Change(path, before == null ? Change.Kind.ADDED : Change.Kind.REMOVED));
        } else if (before.isObject() && after.isObject()) {
            var keys = new TreeSet<>(before.propertyNames());
            keys.addAll(after.propertyNames());
            for (String key : keys) {
                visit(path + "/" + key.replace("~", "~0").replace("/", "~1"), before.get(key), after.get(key));
                if (changes.size() > LIMIT) break;
            }
        } else if (!unorderedEqual(path, before, after)) {
            changes.add(new Change(path, Change.Kind.CHANGED));
        }
    }

    private boolean unorderedEqual(String path, JsonNode before, JsonNode after) {
        if (!(path.endsWith("/required") || path.endsWith("/enum") || path.endsWith("/type"))
            || !before.isArray() || !after.isArray()) return false;
        var left = new HashSet<JsonNode>();
        var right = new HashSet<JsonNode>();
        before.forEach(left::add);
        after.forEach(right::add);
        return left.equals(right);
    }
}
