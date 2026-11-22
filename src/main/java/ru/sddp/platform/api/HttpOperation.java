package ru.sddp.platform.api;

import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** HTTP metadata for an operation imported from an OpenAPI contract. */
public record HttpOperation(String method, String path, boolean requestBody, boolean bodyRequired,
                            List<Parameter> parameters, String bodyContentType, String responseStatus, List<String> bracketFormFields) {
    public static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    public static final String JSON = "application/json";
    public static final String FORM = "application/x-www-form-urlencoded";
    private static final String NAME = "[A-Za-z0-9_.~-]+";
    private static final Pattern QUERY_NAME = Pattern.compile(NAME + "(?:\\[" + NAME + "\\])*");
    private static final Pattern TEMPLATE = Pattern.compile("\\{(" + NAME + ")\\}");
    private static final String PATH_TEXT = "[A-Za-z0-9_/.~-]*+";
    private static final Pattern PATH = Pattern.compile("/" + PATH_TEXT + "(?:\\{" + NAME + "\\}" + PATH_TEXT + ")*");

    public HttpOperation(String method, String path, boolean requestBody, boolean bodyRequired, List<Parameter> parameters) {
        this(method, path, requestBody, bodyRequired, parameters, null, null, List.of());
    }

    public HttpOperation {
        if (method == null || !METHODS.contains(method)) throw new IllegalArgumentException("Unsupported HTTP method");
        if (parameters == null || parameters.size() > 50) throw new IllegalArgumentException("Invalid HTTP parameters");
        parameters = List.copyOf(parameters);
        if (bodyRequired && !requestBody || requestBody && Set.of("GET", "HEAD").contains(method)) {
            throw new IllegalArgumentException("Unsupported request body for " + method);
        }
        if (requestBody && bodyContentType == null) bodyContentType = JSON;
        if (bodyContentType != null && (!requestBody || !Set.of(JSON, FORM).contains(bodyContentType))) {
            throw new IllegalArgumentException("Unsupported HTTP body content type");
        }
        if (responseStatus != null && !responseStatus.matches("2(?:[0-9]{2}|XX)")) {
            throw new IllegalArgumentException("Unsupported HTTP success status");
        }
        bracketFormFields = bracketFormFields == null ? List.of() : List.copyOf(bracketFormFields);
        if (!bracketFormFields.isEmpty() && (!FORM.equals(bodyContentType) || bracketFormFields.size() > 200
            || bracketFormFields.stream().anyMatch(name -> name.length() > 100 || !name.matches(NAME))
            || new HashSet<>(bracketFormFields).size() != bracketFormFields.size())) {
            throw new IllegalArgumentException("Unsupported HTTP form fields");
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
            String encoding = optionalText(parameter, "encoding");
            JsonNode schema = parameter.get("schema");
            parameters.add(new Parameter(parameter.path("name").asText(), parameter.path("in").asText(),
                parameter.path("required").booleanValue(), encoding == null ? Encoding.SCALAR : Encoding.valueOf(encoding),
                schema == null || schema.isNull() ? null : schema));
        }
        var bracketFields = new ArrayList<String>();
        JsonNode fields = node.path("bracketFormFields");
        if (!fields.isMissingNode() && !fields.isNull()) {
            if (!fields.isArray()) throw new IllegalArgumentException("Invalid snapshot HTTP form fields");
            for (JsonNode field : fields) {
                if (!field.isTextual()) throw new IllegalArgumentException("Invalid snapshot HTTP form field");
                bracketFields.add(field.asText());
            }
        }
        return new HttpOperation(node.path("method").asText(), node.path("path").asText(), node.path("requestBody").booleanValue(),
            node.path("bodyRequired").booleanValue(), parameters, optionalText(node, "bodyContentType"),
            optionalText(node, "responseStatus"), bracketFields);
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("Invalid snapshot HTTP " + field);
        return value.asText();
    }

    public enum Encoding { SCALAR, ARRAY, ARRAY_CSV, OBJECT, OBJECT_CSV, DEEP_OBJECT, DEEP_OBJECT_OR_SCALAR, BRACKET_ARRAY }

    public record Parameter(String name, String in, boolean required, Encoding encoding, JsonNode schema) {
        public Parameter(String name, String in, boolean required) { this(name, in, required, Encoding.SCALAR, null); }

        public Parameter {
            if (encoding == null) encoding = Encoding.SCALAR;
            if (name == null || name.length() > 100 || (!"path".equals(in) && !"query".equals(in))
                || ("path".equals(in) && (!required || !name.matches(NAME) || encoding != Encoding.SCALAR))
                || ("query".equals(in) && !QUERY_NAME.matcher(name).matches())
                || (schema != null && !schema.isObject() && !schema.isBoolean())) {
                throw new IllegalArgumentException("Unsupported HTTP parameter");
            }
        }
    }
}
