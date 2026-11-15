package ru.sddp.platform.service;

/** Raised when a write targets a protected environment without explicit confirmation. */
public class ProtectedEnvironmentException extends RuntimeException {
    public ProtectedEnvironmentException(String message) { super(message); }
}
