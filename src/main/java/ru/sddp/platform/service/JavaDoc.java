package ru.sddp.platform.service;

import java.util.List;
import java.util.stream.Collectors;

final class JavaDoc {
    private JavaDoc() { }

    static List<String> text(String description) {
        if (description == null || description.isBlank()) return List.of();

        // Protect both Java's source parser and Javadoc's HTML and tag parser.
        return description.strip()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\\", "&#92;")
            .replace("*/", "*&#47;")
            .replace("@", "&#64;")
            .lines().map(String::stripTrailing).toList();
    }

    static String comment(String indent, List<String> lines) {
        if (lines.isEmpty()) return "";

        return indent + "/**\n" + lines.stream()
            .map(line -> indent + (line.isEmpty() ? " *\n" : " * " + line + "\n"))
            .collect(Collectors.joining()) + indent + " */\n";
    }
}
