package com.openbid.db;

@FunctionalInterface
public interface SqlWork<T> {
    T run() throws Exception;
}
