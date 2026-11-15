package ru.sddp.platform.service;

import tools.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.custom.Quantity;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ru.sddp.platform.api.ApiModels;
import ru.sddp.platform.api.CodegenModels;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.io.ByteArrayInputStream;

/** Runs a registered plugin with a read-only ConfigMap input and extracts its output archive from pod logs. */
@Component
@Profile("k8s")
public class KubernetesJobPluginExecutor implements PluginExecutor {
    private static final String BEGIN = "SDDP_OUTPUT_BEGIN";
    private static final String END = "SDDP_OUTPUT_END";
    private static final int MAX_COMPRESSED = 5_000_000;
    private static final int MAX_ENCODED = ((MAX_COMPRESSED + 2) / 3) * 4;
    private static final int MAX_LOG = 7_000_000;
    private final ObjectMapper mapper;
    private final String namespace;
    private final long pollMillis;

    public KubernetesJobPluginExecutor(ObjectMapper mapper) {
        this.mapper = mapper;
        this.namespace = System.getenv().getOrDefault("SDDP_K8S_NAMESPACE", "default");
        String poll = System.getenv().getOrDefault("SDDP_K8S_POLL_SECONDS", "2");
        try { this.pollMillis = Math.round(Double.parseDouble(poll) * 1000); }
        catch (NumberFormatException e) { throw new IllegalStateException("Invalid SDDP_K8S_POLL_SECONDS: " + poll); }
        if (pollMillis < 500) throw new IllegalStateException("SDDP_K8S_POLL_SECONDS must be at least 0.5");
    }

    @Override
    public CodegenModels.PluginExecutionResult execute(ApiModels.IrManifest manifest, CodegenModels.PluginDescriptor plugin) {
        String name = "sddp-" + UUID.randomUUID().toString().substring(0, 12);
        boolean configCreated = false, jobCreated = false;
        CoreV1Api core = null; BatchV1Api batch = null;
        try {
            var client = ClientBuilder.standard().build();
            core = new CoreV1Api(client); batch = new BatchV1Api(client);
            String ir = mapper.writeValueAsString(manifest);
            if (ir.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 800_000) throw new IllegalArgumentException("IR exceeds ConfigMap size limit");
            core.createNamespacedConfigMap(namespace, new V1ConfigMap().metadata(new V1ObjectMeta().name(name)).data(Map.of("ir.json", ir))).execute();
            configCreated = true;
            var input = new V1Volume().name("input").configMap(new V1ConfigMapVolumeSource().name(name));
            var output = new V1Volume().name("output").emptyDir(new V1EmptyDirVolumeSource().sizeLimit(Quantity.fromString("16Mi")));
            var mounts = List.of(new V1VolumeMount().name("input").mountPath("/input").readOnly(true), new V1VolumeMount().name("output").mountPath("/output"));
            String command = "plugin /input/ir.json /output || exit $?; echo " + BEGIN + "; tar -czf - -C /output . | base64 | tr -d '\\n'; echo; echo " + END;
            var container = new V1Container().name("plugin").image(plugin.executableTarget()).imagePullPolicy("IfNotPresent").command(List.of("/bin/sh", "-c", command)).volumeMounts(mounts)
                .resources(new V1ResourceRequirements().requests(Map.of("cpu", Quantity.fromString("100m"), "memory", Quantity.fromString("128Mi"), "ephemeral-storage", Quantity.fromString("16Mi")))
                    .limits(Map.of("cpu", Quantity.fromString("1"), "memory", Quantity.fromString("512Mi"), "ephemeral-storage", Quantity.fromString("32Mi"))))
                .securityContext(new V1SecurityContext().allowPrivilegeEscalation(false).readOnlyRootFilesystem(true).capabilities(new V1Capabilities().drop(List.of("ALL"))));
            var pod = new V1PodSpec().restartPolicy("Never").automountServiceAccountToken(false).containers(List.of(container)).volumes(List.of(input, output));
            var job = new V1Job().metadata(new V1ObjectMeta().name(name)).spec(new V1JobSpec().backoffLimit(0).activeDeadlineSeconds(300L).template(new V1PodTemplateSpec().spec(pod)));
            batch.createNamespacedJob(namespace, job).execute(); jobCreated = true;
            Instant deadline = Instant.now().plus(Duration.ofMinutes(6));
            while (Instant.now().isBefore(deadline)) {
                var status = batch.readNamespacedJobStatus(name, namespace).execute().getStatus();
                if (status != null && status.getFailed() != null && status.getFailed() > 0) throw new PluginGenerationException(1, "Kubernetes Job failed: " + name + ": " + podLogs(core, name));
                if (status != null && status.getSucceeded() != null && status.getSucceeded() > 0) {
                    String logs = podLogs(core, name);
                    return new CodegenModels.PluginExecutionResult(0, "", "", extract(logs));
                }
                Thread.sleep(pollMillis);
            }
            throw new PluginGenerationException(-1, "Kubernetes Job timed out: " + name);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new PluginGenerationException(-1, "Kubernetes Job interrupted"); }
        catch (PluginGenerationException e) { throw e; }
        catch (Exception e) { throw new PluginGenerationException(-1, "Kubernetes execution failed: " + e.getMessage()); }
        finally {
            if (jobCreated) try { batch.deleteNamespacedJob(name, namespace).propagationPolicy("Foreground").execute(); } catch (Exception ignored) { }
            if (configCreated) try { core.deleteNamespacedConfigMap(name, namespace).execute(); } catch (Exception ignored) { }
        }
    }
    private String podLogs(CoreV1Api core, String name) throws Exception {
        var pods = core.listNamespacedPod(namespace).labelSelector("job-name=" + name).execute().getItems();
        if (pods.isEmpty()) throw new IllegalStateException("Job pod not found");
        return core.readNamespacedPodLog(pods.getFirst().getMetadata().getName(), namespace).container("plugin").limitBytes(MAX_LOG).execute();
    }
    static Map<String,String> extract(String logs) throws Exception {
        int start = logs.lastIndexOf(BEGIN), end = logs.indexOf(END, start < 0 ? 0 : start);
        if (start < 0 || end < 0) throw new IllegalArgumentException("Plugin output archive is missing");
        String encoded = logs.substring(start + BEGIN.length(), end).trim();
        if (encoded.length() > MAX_ENCODED) throw new IllegalArgumentException("Plugin output archive too large");
        byte[] compressed = Base64.getDecoder().decode(encoded);
        if (compressed.length > MAX_COMPRESSED) throw new IllegalArgumentException("Plugin output archive too large");
        return DockerPluginExecutor.extract(new GZIPInputStream(new ByteArrayInputStream(compressed)), false);
    }
}
