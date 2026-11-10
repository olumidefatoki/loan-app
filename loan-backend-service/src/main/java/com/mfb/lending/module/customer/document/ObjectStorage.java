package com.mfb.lending.module.customer.document;

/**
 * Private object storage. Keys are internal and are never published as URLs.
 */
public interface ObjectStorage {

    void put(String key, byte[] body);

    byte[] get(String key);

    void delete(String key);
}
