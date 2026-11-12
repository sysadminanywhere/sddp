package ru.sddp.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ru.sddp.platform.api.*;
import java.nio.file.*;
import java.util.UUID;

@Component
@Profile("docker")
public class DockerPluginExecutor implements PluginExecutor {
    private final ObjectMapper mapper;
    public DockerPluginExecutor(ObjectMapper mapper) { this.mapper = mapper; }
    @Override
    public CodegenModels.PluginExecutionResult execute(ApiModels.IrManifest manifest, CodegenModels.PluginDescriptor plugin) {
        Path shared = null; String containerId = null; DockerClient client = null;
        try {
            shared = Files.createTempDirectory("sddp-" + UUID.randomUUID());
            Path input = Files.createDirectories(shared.resolve("input"));
            Path output = Files.createDirectories(shared.resolve("output"));
            Files.writeString(input.resolve("ir.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
            var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
            var transport = new ZerodepDockerHttpClient.Builder().dockerHost(config.getDockerHost()).sslConfig(config.getSSLConfig()).build();
            client = DockerClientBuilder.getInstance(config).withDockerHttpClient(transport).build();
            HostConfig hostConfig = new HostConfig().withNetworkMode("none").withMemory(536870912L).withNanoCPUs(1000000000L).withBinds(
                    new Bind(input.toAbsolutePath().toString(), new Volume("/input"), com.github.dockerjava.api.model.AccessMode.ro),
                    new Bind(output.toAbsolutePath().toString(), new Volume("/output"), com.github.dockerjava.api.model.AccessMode.rw));
            containerId = client.createContainerCmd(plugin.executableTarget())
                    .withHostConfig(hostConfig).withCmd("/bin/sh", "-c", "plugin /input/ir.json /output").exec().getId();
            client.startContainerCmd(containerId).exec();
            Integer exit;
            try (WaitContainerResultCallback callback = new WaitContainerResultCallback()) {
                client.waitContainerCmd(containerId).exec(callback);
                if (!callback.awaitCompletion(5, java.util.concurrent.TimeUnit.MINUTES)) {
                    throw new PluginGenerationException(-1, "Plugin timed out after 5 minutes");
                }
                exit = callback.awaitStatusCode();
            }
            StringBuilder stderr = new StringBuilder();
            client.logContainerCmd(containerId).withStdErr(true).withStdOut(false).withFollowStream(false)
                    .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<Frame>() {
                        @Override public void onNext(Frame frame) { stderr.append(new String(frame.getPayload(), java.nio.charset.StandardCharsets.UTF_8)); }
                    }).awaitCompletion();
            if (exit != 0) throw new PluginGenerationException(exit, stderr.toString());
            java.util.Map<String,String> files = new java.util.TreeMap<>();
            try (var paths = Files.walk(output)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("Plugin output contains a symlink");
                    if (Files.size(path) > 5_000_000) throw new IllegalArgumentException("Plugin output file too large");
                    files.put(output.relativize(path).toString().replace('\\', '/'), Files.readString(path));
                }
            }
            return new CodegenModels.PluginExecutionResult(exit, "", stderr.toString(), files);
        } catch (PluginGenerationException e) { throw e; }
        catch (Exception e) { throw new PluginGenerationException(-1, e.getMessage() == null ? e.toString() : e.getMessage()); }
        finally {
            if (containerId != null && client != null) try { client.removeContainerCmd(containerId).withForce(true).exec(); } catch (Exception ignored) { }
            if (client != null) try { client.close(); } catch (Exception ignored) { }
            if (shared != null) try (var files = Files.walk(shared)) { files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) { } }); } catch (Exception ignored) { }
        }
    }
}
