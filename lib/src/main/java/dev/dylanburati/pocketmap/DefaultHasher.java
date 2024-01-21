package dev.dylanburati.pocketmap;

import java.nio.ByteBuffer;

/* package-private */ class DefaultHasher implements Hasher {
  private static DefaultHasher instance = null;

  private DefaultHasher() {}

  static DefaultHasher instance() {
    if (instance == null) {
      instance = new DefaultHasher();
    }
    return instance;
  }

  @Override
  public int hashBytes(byte[] keyContent) {
    int h = 1;
    for (int offset = keyContent.length - 1; offset >= 0; offset--) {
      h = 31 * h + (int)keyContent[offset];
    }
    return h;
  }

  @Override
  public int hashBuffer(ByteBuffer buf, int position, int length) {
    int h = 1;
    byte[] arr = buf.array();
    for (int offset = position + length - 1; offset >= position; offset--) {
      h = 31 * h + (int)arr[offset];
    }
    return h;
  }
}
