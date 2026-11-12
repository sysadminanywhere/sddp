package ru.sddp.platform.service;

import ru.sddp.platform.api.CodegenModels;
import ru.sddp.platform.api.ApiModels;

public interface PluginExecutor {
    CodegenModels.PluginExecutionResult execute(ApiModels.IrManifest manifest, CodegenModels.PluginDescriptor plugin);
}
