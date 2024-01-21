package dev.dylanburati.shrinkwrap;

import java.nio.ByteBuffer;

/**
 * Computes hashes for insertion to compact maps. The rules of {@link Object.hashCode}
 * also apply here.
 */
public interface Hasher {
  int hashBytes(byte[] data);
  int hashBuffer(ByteBuffer buf, int offset, int length);
}
