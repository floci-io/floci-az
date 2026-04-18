package io.floci.az.core;

public record AuthContext(
    String accountName,
    AuthType type,
    boolean isValid
) {}
