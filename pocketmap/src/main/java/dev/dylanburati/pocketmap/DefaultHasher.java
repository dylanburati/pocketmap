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
    return this.hashImpl(keyContent, 0, keyContent.length);
  }

  @Override
  public int hashBuffer(ByteBuffer buf, int position, int length) {
    return this.hashImpl(buf.array(), position, length);
  }

  private int hashImpl(byte[] data, int position, int length) {
    // https://github.com/cbreeden/fxhash/blob/master/lib.rs
    int h = 1;
    for (int offset = position + length - 1; offset >= position; offset--) {
      h = 31 * h + (int)data[offset];
    }
    return h;
  }
}
