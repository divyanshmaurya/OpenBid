package com.openbid.db;

public record UserRecord(long id, String username, byte[] salt, byte[] passwordHash, long createdAt) {}
