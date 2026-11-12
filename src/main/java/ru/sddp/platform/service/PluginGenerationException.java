package ru.sddp.platform.service;
public class PluginGenerationException extends RuntimeException {
    private final int exitCode;
    public PluginGenerationException(int exitCode, String stderr) { super("Plugin generation failed with exit code " + exitCode + ": " + stderr); this.exitCode = exitCode; }
    public int getExitCode() { return exitCode; }
}
