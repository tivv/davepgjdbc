package org.postgresql.core;

/**
 * This is callback that will be called when data is available to read from PGStream
 */
public interface StreamReadListener {
    void canRead();
}
