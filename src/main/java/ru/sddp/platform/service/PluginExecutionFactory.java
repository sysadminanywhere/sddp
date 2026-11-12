package ru.sddp.platform.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import ru.sddp.platform.api.CodegenModels;

/** Resolves a delivery format independently of the deployment profile. */
@Component
public class PluginExecutionFactory {
    private final ObjectProvider<PluginExecutor> executors;
    public PluginExecutionFactory(ObjectProvider<PluginExecutor> executors) { this.executors = executors; }
    public PluginExecutor resolve(CodegenModels.PluginDescriptor plugin) {
        if (plugin == null || !"DOCKER".equals(plugin.deliveryType())) throw new IllegalArgumentException("Unsupported plugin delivery type; only DOCKER container images are supported");
        var active = executors.orderedStream().toList();
        if (active.size() != 1) throw new IllegalStateException("Exactly one of the docker or k8s profiles must be active for plugin execution");
        return active.getFirst();
    }
}
