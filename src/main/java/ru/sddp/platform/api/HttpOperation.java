package ru.sddp.platform.api;

import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** HTTP metadata for an operation imported from an OpenAPI contract. */
public record HttpOperation(String method, String path, boolean requestBody, boolean bodyRequired,
                            List<Parameter> parameters) {
    public static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final String NAME = "[A-Za-z0-9_.~-]+";
    private static final Pattern TEMPLATE = Pattern.compile("\\{(" + NAME + ")\\}");
    private static final String PATH_TEXT = "[A-Za-z0-9_/.~-]*+";
    private static final Pattern PATH = Pattern.compile("/" + PATH_TEXT + "(?:\\{" + NAME + "\\}" + PATH_TEXT + ")*");

    public HttpOperation {
        if (method == null || !METHODS.contains(method)) throw new IllegalArgumentException("Unsupported HTTP method");
        if (parameters == null || parameters.size() > 50) throw new IllegalArgumentException("Invalid HTTP parameters");
        parameters = List.copyOf(parameters);
        if (bodyRequired && !requestBody || requestBody && Set.of("GET", "HEAD").contains(method)) {
            throw new IllegalArgumentException("Unsupported request body for " + method);
        }
        var declared = new HashSet<String>();
        var pathParameters = new HashSet<String>();
        for (Parameter parameter : parameters) {
            if (!declared.add(parameter.in() + ":" + parameter.name())) throw new IllegalArgumentException("Duplicate HTTP parameter");
            if ("path".equals(parameter.in())) pathParameters.add(parameter.name());
        }
        if (!pathParameters.equals(placeholders(path))) throw new IllegalArgumentException("Path template and parameters differ");
    }

    public static Set<String> placeholders(String path) {
        if (path == null || path.length() > 2048 || !PATH.matcher(path).matches() || path.contains("//")
            || java.util.Arrays.stream(path.split("/")).anyMatch(segment -> segment.equals(".") || segment.equals(".."))) {
            throw new IllegalArgumentException("Unsupported HTTP path");
        }
        var names = new HashSet<String>();
        TEMPLATE.matcher(path).results().forEach(match -> names.add(match.group(1)));
        return names;
    }

    public static HttpOperation fromSnapshot(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        if (!node.isObject() || !node.path("method").isTextual() || !node.path("path").isTextual()
            || !node.path("requestBody").isBoolean() || !node.path("bodyRequired").isBoolean()
            || !node.path("parameters").isArray()) throw new IllegalArgumentException("Invalid snapshot HTTP operation");
        var parameters = new ArrayList<Parameter>();
        for (JsonNode parameter : node.path("parameters")) {
            if (!parameter.path("name").isTextual() || !parameter.path("in").isTextual() || !parameter.path("required").isBoolean()) {
                throw new IllegalArgumentException("Invalid snapshot HTTP parameter");
            }
            parameters.add(new Parameter(parameter.path("name").asText(), parameter.path("in").asText(),
                parameter.path("required").booleanValue()));
        }
        return new HttpOperation(node.path("method").asText(), node.path("path").asText(), node.path("requestBody").booleanValue(),
            node.path("bodyRequired").booleanValue(), parameters);
    }

    public record Parameter(String name, String in, boolean required) {
        public Parameter {
            if (name == null || name.length() > 100 || !name.matches(NAME)
                || (!"path".equals(in) && !"query".equals(in)) || ("path".equals(in) && !required)) {
                throw new IllegalArgumentException("Unsupported HTTP parameter");
            }
        }
    }
}
