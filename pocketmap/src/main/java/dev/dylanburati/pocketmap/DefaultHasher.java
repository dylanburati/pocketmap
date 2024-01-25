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

  private static final int KEY32 = (int) 0x9e_37_79_b9L;

  private int hashImpl(byte[] data, int position, int length) {
    // https://github.com/cbreeden/fxhash/blob/master/lib.rs
    int hash = 0;
    int i = 0;
    int word;
    for ( ; i + 4 <= length; i += 4) {
      word = (data[position+i] & 0xff)
        | ((data[position+i+1] & 0xff) << 8)
        | ((data[position+i+2] & 0xff) << 16)
        | ((data[position+i+3] & 0xff) << 24);
      hash = ((hash << 5) ^ word) * KEY32;
    }
    switch (length - i) {
      case 0:
        return hash;
      case 1:
        word = data[position+i] & 0xff;
        return ((hash << 5) ^ word) * KEY32;
      case 2:
        word = (data[position+i] & 0xff)
          | ((data[position+i+1] & 0xff) << 8);
        return ((hash << 5) ^ word) * KEY32;
      case 3:
        word = (data[position+i] & 0xff)
          | ((data[position+i+1] & 0xff) << 8)
          | ((data[position+i+2] & 0xff) << 16);
        return ((hash << 5) ^ word) * KEY32;
      default:
        throw new RuntimeException();
    }
  }
}
