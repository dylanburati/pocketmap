package dev.dylanburati.pocketmap;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/* package-private */ class KeyStorage {
  static final int KEY_OFFSET_BITS = 22;
  static final int BUF_SIZE = 1 << KEY_OFFSET_BITS; // 4 MiB
  static final int KEY_OFFSET_MASK = BUF_SIZE - 1;

  static final int KEY_LEN_BITS = 20;
  static final int KEY_LEN_LIMIT = 1 << KEY_LEN_BITS;  // 1 MiB
  static final int KEY_LEN_MASK = KEY_LEN_LIMIT - 1;

  static final int BUFNR_BITS = 20;  // total = 4 TiB
  static final int BUFNR_LIMIT = 1 << BUFNR_BITS;
  static {
    assertBitsAreRight();
  }

  @SuppressWarnings("all")
  private static void assertBitsAreRight() {
    if (BUFNR_BITS + KEY_OFFSET_BITS + KEY_LEN_BITS + 2 != 64) {
      throw new AssertionError();
    }
  }

  final Hasher hasher;
  private final List<ByteBuffer> buffers;

  KeyStorage(final Hasher hasher) {
    this.hasher = hasher;
    this.buffers = new ArrayList<>();
    this.buffers.add(ByteBuffer.allocate(BUF_SIZE));
  }
  
  // bits[63:23] = offset
  //     [22:2]  = length
  //     [2:0]   = tombstone flag and present/empty flag
  long store(byte[] keyContent) {
    return this.store(keyContent, 0, keyContent.length);
  }

  private long store(byte[] src, int srcOffset, int srcLength) {
    if (srcLength >= KEY_LEN_LIMIT) {
      throw new IllegalArgumentException("Key too long");
    }
    int which = this.buffers.size() - 1;
    ByteBuffer store = this.buffers.get(which);
    if (store.remaining() < srcLength) {
      which += 1;
      assert which < BUFNR_LIMIT;
      store = ByteBuffer.allocate(BUF_SIZE);
      this.buffers.add(store);
    }
    int offset = store.position();
    store.put(src, srcOffset, srcLength);
    return ((long) which << (KEY_OFFSET_BITS + KEY_LEN_BITS + 2))
      | ((long) offset << (KEY_LEN_BITS + 2))
      | ((long) srcLength << 2)
      | 3;
  }

  byte[] load(long keyRef) {
    int which = (int) (keyRef >>> (KEY_OFFSET_BITS + KEY_LEN_BITS + 2));
    int offset = (int) ((keyRef >>> (KEY_LEN_BITS + 2)) & KEY_OFFSET_MASK);
    int length = (int) ((keyRef >>> 2) & KEY_LEN_MASK);
    byte[] bufContent = this.buffers.get(which).array();
    return Arrays.copyOfRange(bufContent, offset, offset + length);
  }

  String loadAsString(long keyRef, Charset charset) {
    int which = (int) (keyRef >>> (KEY_OFFSET_BITS + KEY_LEN_BITS + 2));
    int offset = (int) ((keyRef >>> (KEY_LEN_BITS + 2)) & KEY_OFFSET_MASK);
    int length = (int) ((keyRef >>> 2) & KEY_LEN_MASK);
    byte[] bufContent = this.buffers.get(which).array();
    return new String(bufContent, offset, length, charset);
  }

  int hashAt(long keyRef) {
    int which = (int) (keyRef >>> (KEY_OFFSET_BITS + KEY_LEN_BITS + 2));
    int offset = (int) ((keyRef >>> (KEY_LEN_BITS + 2)) & KEY_OFFSET_MASK);
    int length = (int) ((keyRef >>> 2) & KEY_LEN_MASK);
    return this.hasher.hashBuffer(this.buffers.get(which), offset, length);
  }

  boolean equalsAt(long keyRef, byte[] other) {
    int which = (int) (keyRef >>> (KEY_OFFSET_BITS + KEY_LEN_BITS + 2));
    int offset = (int) ((keyRef >>> (KEY_LEN_BITS + 2)) & KEY_OFFSET_MASK);
    int length = (int) ((keyRef >>> 2) & KEY_LEN_MASK);
    if (other.length != length) {
      return false;
    }
    byte[] bufContent = this.buffers.get(which).array();
    return Arrays.equals(bufContent, offset, offset + length, other, 0, length);
  }

  public long copyFrom(KeyStorage src, long keyRef) {
    int which = (int) (keyRef >>> (KEY_OFFSET_BITS + KEY_LEN_BITS + 2));
    int offset = (int) ((keyRef >>> (KEY_LEN_BITS + 2)) & KEY_OFFSET_MASK);
    int length = (int) ((keyRef >>> 2) & KEY_LEN_MASK);
    byte[] bufContent = src.buffers.get(which).array();
    return this.store(bufContent, offset, length);
  }
}
