package ru.sddp.platform.service;

import tools.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ru.sddp.platform.api.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
@Profile("docker")
public class DockerPluginExecutor implements PluginExecutor {
    private static final long MAX_FILE = 5_000_000, MAX_TOTAL = 10_000_000;
    private static final int MAX_ENTRIES = 500, MAX_LOG = 65_536;
    private final ObjectMapper mapper;
    public DockerPluginExecutor(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public CodegenModels.PluginExecutionResult execute(ApiModels.IrManifest manifest, CodegenModels.PluginDescriptor plugin) {
        Path shared = null; String containerId = null; DockerClient client = null;
        try {
            shared = Files.createTempDirectory("sddp-");
            Path input = Files.createDirectories(shared.resolve("input"));
            Files.writeString(input.resolve("ir.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
            var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
            var transport = new ZerodepDockerHttpClient.Builder().dockerHost(config.getDockerHost()).sslConfig(config.getSSLConfig()).build();
            client = DockerClientImpl.getInstance(config, transport);
            HostConfig hostConfig = new HostConfig().withNetworkMode("none").withMemory(536870912L).withNanoCPUs(1000000000L)
                .withPidsLimit(128L).withReadonlyRootfs(true).withLogConfig(new LogConfig(LogConfig.LoggingType.JSON_FILE, Map.of("max-size", "1m", "max-file", "1")))
                .withBinds(new Bind(input.toAbsolutePath().toString(), new Volume("/input"), com.github.dockerjava.api.model.AccessMode.ro))
                .withTmpFs(Map.of("/output", "rw,nosuid,nodev,noexec,size=16777216,nr_inodes=1024", "/control", "rw,nosuid,nodev,noexec,size=65536,nr_inodes=32", "/tmp", "rw,nosuid,nodev,noexec,size=8388608,nr_inodes=1024"));
            // Keep the container alive after completion: Docker removes tmpfs contents on stop.
            String command = "plugin /input/ir.json /output; result=$?; echo $result > /control/result.tmp; mv /control/result.tmp /control/result; while :; do sleep 10; done";
            containerId = client.createContainerCmd(plugin.executableTarget()).withHostConfig(hostConfig)
                .withCmd("/bin/sh", "-c", command).exec().getId();
            client.startContainerCmd(containerId).exec();
            long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
            Integer exit = null;
            while (System.nanoTime() < deadline) {
                var state = client.inspectContainerCmd(containerId).exec().getState();
                if (!Boolean.TRUE.equals(state.getRunning())) throw new PluginGenerationException(-1, "Plugin container stopped before reporting its result");
                var exec = client.execCreateCmd(containerId).withAttachStdout(true)
                    .withCmd("/bin/sh", "-c", "test -f /control/result && head -c 16 /control/result").exec();
                ByteArrayOutputStream status = new ByteArrayOutputStream();
                try (var callback = new ResultCallback.Adapter<Frame>() {
                    @Override public void onNext(Frame frame) { if (status.size() < 16) status.write(frame.getPayload(), 0, Math.min(frame.getPayload().length, 16 - status.size())); }
                }) {
                    if (!client.execStartCmd(exec.getId()).exec(callback).awaitCompletion(5, TimeUnit.SECONDS)) throw new PluginGenerationException(-1, "Plugin status check timed out");
                }
                if (Objects.equals(client.inspectExecCmd(exec.getId()).exec().getExitCodeLong(), 0L)) {
                    String value = status.toString(StandardCharsets.UTF_8).trim();
                    if (!value.matches("[0-9]{1,3}")) throw new PluginGenerationException(-1, "Invalid plugin exit status");
                    exit = Integer.parseInt(value);
                    if (exit > 255) throw new PluginGenerationException(-1, "Invalid plugin exit status");
                    break;
                }
                Thread.sleep(500);
            }
            if (exit == null) throw new PluginGenerationException(-1, "Plugin timed out after 5 minutes");
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            try (var callback = new ResultCallback.Adapter<Frame>() {
                @Override public void onNext(Frame frame) { if (stderr.size() < MAX_LOG) stderr.write(frame.getPayload(), 0, Math.min(frame.getPayload().length, MAX_LOG - stderr.size())); }
            }) {
                client.logContainerCmd(containerId).withStdErr(true).withStdOut(false).withFollowStream(false).exec(callback).awaitCompletion();
            }
            String error = stderr.toString(StandardCharsets.UTF_8);
            if (exit != 0) throw new PluginGenerationException(exit, error);
            // Docker's archive API does not expose tmpfs contents; stream a bounded tar from inside the container.
            var archiveExec = client.execCreateCmd(containerId).withAttachStdout(true)
                .withCmd("/bin/sh", "-c", "tar -cf - -C /output .").exec();
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            boolean[] oversized = {false};
            int maxArchive = 12_000_000;
            try (var callback = new ResultCallback.Adapter<Frame>() {
                @Override public void onNext(Frame frame) {
                    byte[] data = frame.getPayload();
                    if (data.length > maxArchive - archive.size()) { oversized[0] = true; return; }
                    if (!oversized[0]) archive.write(data, 0, data.length);
                }
            }) {
                if (!client.execStartCmd(archiveExec.getId()).exec(callback).awaitCompletion(30, TimeUnit.SECONDS)) throw new IllegalArgumentException("Plugin output archive timed out");
            }
            if (oversized[0] || !Objects.equals(client.inspectExecCmd(archiveExec.getId()).exec().getExitCodeLong(), 0L)) throw new IllegalArgumentException("Plugin output archive is too large or could not be read");
            return new CodegenModels.PluginExecutionResult(exit, "", error, extract(new java.io.ByteArrayInputStream(archive.toByteArray())));
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new PluginGenerationException(-1, "Plugin execution interrupted"); }
        catch (PluginGenerationException e) { throw e; }
        catch (Exception e) { throw new PluginGenerationException(-1, e.getMessage() == null ? e.toString() : e.getMessage()); }
        finally {
            if (containerId != null && client != null) try { client.removeContainerCmd(containerId).withForce(true).exec(); } catch (Exception ignored) { }
            if (client != null) try { client.close(); } catch (Exception ignored) { }
            if (shared != null) try (var files = Files.walk(shared)) { files.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) { } }); } catch (Exception ignored) { }
        }
    }

    static Map<String, String> extract(InputStream archive) throws Exception {
        return extract(archive, true);
    }

    static Map<String, String> extract(InputStream archive, boolean stripOutputPrefix) throws Exception {
        Map<String, String> files = new TreeMap<>(); long total = 0; int entries = 0;
        try (var tar = new TarArchiveInputStream(archive)) {
            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IllegalArgumentException("Too many plugin output entries");
                String path = entry.getName().replaceFirst("^\\./", "");
                if (path.equals(".") || path.isEmpty()) continue;
                if (stripOutputPrefix && path.startsWith("output/")) path = path.substring("output/".length());
                if (path.isEmpty()) continue;
                if (path.length() > 512 || path.startsWith("/") || path.contains("\\") || Arrays.asList(path.split("/")).contains("..")) throw new IllegalArgumentException("Unsafe plugin output path");
                if (entry.isSymbolicLink() || entry.isLink()) throw new IllegalArgumentException("Unsafe plugin output link");
                if (entry.isDirectory()) continue;
                if (!entry.isFile() || entry.getSize() < 0 || entry.getSize() > MAX_FILE || (total += entry.getSize()) > MAX_TOTAL) throw new IllegalArgumentException("Unsafe or oversized plugin output");
                byte[] bytes = tar.readNBytes((int) entry.getSize());
                if (bytes.length != entry.getSize()) throw new IllegalArgumentException("Invalid plugin output archive");
                String content = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                if (files.putIfAbsent(path, content) != null) throw new IllegalArgumentException("Duplicate plugin output path");
            }
        }
        return files;
    }
}
