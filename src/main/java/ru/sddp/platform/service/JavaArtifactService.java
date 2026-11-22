package ru.sddp.platform.service;

import freemarker.template.Configuration;
import freemarker.template.TemplateException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.lang.model.SourceVersion;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.sddp.platform.api.CodegenModels;
import ru.sddp.platform.api.TopologySnapshot;
import tools.jackson.databind.ObjectMapper;

@Service
public class JavaArtifactService {
    private static final LocalDateTime ZIP_ENTRY_TIME = LocalDateTime.of(1980, 1, 2, 0, 0);
    private final CodeGenerationService codegen;
    private final SnapshotExportService snapshots;
    private final BuildProperties build;
    private final Configuration freemarker;
    private final ObjectMapper mapper;

    public JavaArtifactService(CodeGenerationService codegen, SnapshotExportService snapshots, BuildProperties build,
                               Configuration freemarker, ObjectMapper mapper) {
        this.codegen = codegen;
        this.snapshots = snapshots;
        this.build = build;
        this.freemarker = freemarker;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] build(CodegenModels.ArtifactRequest request) {
        if (!SourceVersion.isName(request.packageName()) || !SourceVersion.isIdentifier(request.clientName())
            || SourceVersion.isKeyword(request.clientName())
            || !request.packageName().matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
            || !request.clientName().matches("[A-Za-z_][A-Za-z0-9_]*")
            || Set.of("var", "yield", "record", "sealed", "permits").contains(request.clientName())) {
            throw new IllegalArgumentException("Package and client names must be valid Java identifiers");
        }
        var sources = codegen.generateArtifact(new CodegenModels.GenerateRequest(request.projectId(),
            request.environment(), request.packageName(), request.clientName(), null)).files();
        var snapshot = snapshots.export(request.projectId(), request.environment());
        String artifactId = request.clientName().toLowerCase(Locale.ROOT);
        var files = new TreeMap<String, byte[]>();
        String sourceDirectory = "src/main/java/" + request.packageName().replace('.', '/') + "/";
        sources.forEach((name, source) -> files.put(sourceDirectory + name, utf8(source)));
        files.put("pom.xml", utf8(pom(request, artifactId)));
        var manifest = new Manifest(1, request.packageName(), artifactId, request.version(), build.getVersion(),
            snapshot.projectName(), snapshot.environments().getFirst().code(), contracts(snapshot), checksums(files));
        files.put("sddp-artifact.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
        var checksumFile = new StringBuilder();
        checksums(files).forEach((path, hash) -> checksumFile.append(hash).append("  ").append(path).append('\n'));
        files.put("SHA256SUMS", utf8(checksumFile.toString()));
        return zip(files);
    }

    private List<Contract> contracts(TopologySnapshot snapshot) {
        var services = snapshot.services().stream().collect(Collectors.toMap(TopologySnapshot.Service::id, Function.identity()));
        var boundApis = new HashSet<UUID>();
        for (var connection : snapshot.connections()) {
            if (connection.sourceApiId() != null) boundApis.add(connection.sourceApiId());
            if (connection.targetApiId() != null) boundApis.add(connection.targetApiId());
        }
        return snapshot.apis().stream().filter(api -> boundApis.contains(api.id()))
            .map(api -> new Contract(services.get(api.serviceId()).name(), api.name(), api.version()))
            .sorted(Comparator.comparing(Contract::service).thenComparing(Contract::operation).thenComparing(Contract::version))
            .toList();
    }

    private String pom(CodegenModels.ArtifactRequest request, String artifactId) {
        try {
            var writer = new StringWriter();
            freemarker.getTemplate("java-artifact-pom.xml.ftl").process(Map.of(
                "groupId", request.packageName(), "artifactId", artifactId, "version", request.version(),
                "springBoot", build.get("spring-boot-version"), "jackson", build.get("jackson-2-version"),
                "resilience4j", build.get("resilience4j-version")), writer);
            return writer.toString();
        } catch (IOException | TemplateException e) {
            throw new IllegalStateException("Cannot create the artifact Maven build", e);
        }
    }

    private Map<String, String> checksums(Map<String, byte[]> files) {
        var hashes = new TreeMap<String, String>();
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            files.forEach((path, bytes) -> hashes.put(path, HexFormat.of().formatHex(digest.digest(bytes))));
            return hashes;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private byte[] zip(Map<String, byte[]> files) {
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            for (var file : files.entrySet()) {
                var entry = new ZipEntry(file.getKey());
                entry.setTimeLocal(ZIP_ENTRY_TIME);
                zip.putNextEntry(entry);
                zip.write(file.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot package the Java artifact", e);
        }
        return buffer.toByteArray();
    }

    private byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private record Contract(String service, String operation, String version) { }
    private record Manifest(int formatVersion, String groupId, String artifactId, String version, String generatorVersion,
                            String project, String environment, List<Contract> contracts, Map<String, String> files) { }
}
