package ru.sddp.platform.service;

/** Raised when an entity cannot be removed because other objects still reference it. */
public class ResourceInUseException extends RuntimeException {
    public ResourceInUseException(String message) { super(message); }
}
