package dev.dylanburati.shrinkwrap;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/* template!(2) /**\n * Hash map from strings to \(.primitive)s which minimizes memory overhead at large sizes. */
/**
 * Hash map from strings to ints which minimizes memory overhead at large sizes.
 *
 * Internally, all keys are converted to UTF-8 when inserted, and new keys are
 * pushed into the key storage buffer. Lookups use a {@code long[]} array of
 * references to elements in the storage buffer, and a second primitive array
 * for values. All keys must be smaller than 1048576 bytes.
 *
 * The map doesn't attempt to reclaim the buffer space occupied by deleted keys.
 * To do this manually, clone the map.
 */
public class CompactStringByteMap extends AbstractMap<String, Byte> implements Cloneable {
  private static final int KEY_OFFSET_BITS = 22;
  private static final int BUF_SIZE = 1 << KEY_OFFSET_BITS; // 4 MiB
  private static final int KEY_OFFSET_MASK = BUF_SIZE - 1;

  private static final int KEY_LEN_BITS = 20;
  private static final int KEY_LEN_LIMIT = 1 << KEY_LEN_BITS;  // 1 MiB
  private static final int KEY_LEN_MASK = KEY_LEN_LIMIT - 1;

  private static final int BUFNR_BITS = 20;  // total = 4 TiB
  private static final int BUFNR_LIMIT = 1 << BUFNR_BITS;
  static {
    assertBitsAreRight();
  }

  @SuppressWarnings("all")
  private static void assertBitsAreRight() {
    if (BUFNR_BITS + KEY_OFFSET_BITS + KEY_LEN_BITS + 2 != 64) {
      throw new AssertionError();
    }
  }

  private static final int DEFAULT_CAPACITY = 65536;
  private final KeyStorage keyStorage;
  // INVARIANT 1: keys.length == values.length
  private long[] keys;
  private byte[] values;

  // INVARIANT 2:
  //  2A: size           == count [k | k in keys, (k & 3) == 3]
  //  2B: tombstoneCount == count [k | k in keys, (k & 3) == 1]
  //  2C: 0              == count [k | k in keys, (k & 3) == 2]
  private int size;
  private int tombstoneCount;
  private int rehashCount;

  public CompactStringByteMap() {
    this(DEFAULT_CAPACITY);
  }

  public CompactStringByteMap(int initialCapacity) {
    if (initialCapacity < 0) {
      throw new IllegalArgumentException("expected non-negative initialCapacity");
    }
    int cap = 8;
    if (initialCapacity > 8) {
      // next power of two >= initialCapacity
      cap = 1 << (32 - Integer.numberOfLeadingZeros(initialCapacity - 1));
    }
    this.keyStorage = new KeyStorage();
    // INVARIANT 1 upheld
    this.keys = new long[cap];
    this.values = new byte[cap];
    // INVARIANT 2 upheld, keys is all zeroes
    this.size = 0;
    this.tombstoneCount = 0;
  }

  private CompactStringByteMap(final KeyStorage keyStorage, long[] keys, byte[] values, int size) {
    // clone constructor, invariants are the responsibility of clone()
    this.keyStorage = keyStorage;
    this.keys = keys;
    this.values = values;
    this.size = size;
    this.tombstoneCount = 0;
  }

  @Override
  public int size() {
    return this.size;
  }

  @Override
  public boolean isEmpty() {
    return this.size == 0;
  }

  @Override
  public boolean containsKey(Object key) {
    if (!(key instanceof String)) {
      return false;
    }
    byte[] keyContent = ((String) key).getBytes(StandardCharsets.UTF_8);
    return this.readIndex(keyContent) >= 0;
  }

  private boolean containsEntry(Map.Entry<?, ?> e) {
    Object key = e.getKey();
    Object value = e.getValue();
    if (!(key instanceof String)) {
      return false;
    }
    if (!(value instanceof Byte)) {
      return false;
    }
    byte[] keyContent = ((String) key).getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    return idx >= 0 && this.values[idx] == (Byte) value;
  }

  @Override
  public boolean containsValue(Object value) {
    if (!(value instanceof Byte)) {
      return false;
    }
    byte needle = (Byte) value;
    for (int src = 0; src < this.keys.length; src++) {
      if ((this.keys[src] & 3) == 3 && this.values[src] == needle) {
        return true;
      }
    }
    return false;
  }

  @Override
  public Byte get(Object key) {
    if (!(key instanceof String)) {
      return null;
    }
    byte[] keyContent = ((String) key).getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    if (idx < 0) {
      return null;
    }
    return this.values[idx];
  }

  @Override
  public Byte put(String key, Byte value) {
    // System.err.println("put");
    byte[] keyContent = key.getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    if (idx >= 0) {
      Byte prev = this.values[idx];
      this.values[idx] = value;
      return prev;
    }
    idx = -idx - 1;
    boolean isTombstone = (this.keys[idx] & 1) == 1;

    int cap = this.keys.length;
    if (!isTombstone && this.size + this.tombstoneCount + 1 > cap * 7 / 8) {
      if (this.size + 1 > cap * 3 / 4) {
        this.setCapacity(cap << 1);
      } else {
        this.setCapacity(cap);
      }
      idx = this.insertionIndex(keyContent);
      isTombstone = false;  // no tombstones following resize
    }
    long keyRef = this.keyStorage.store(keyContent);
    this.keys[idx] = keyRef;
    this.values[idx] = value;
    // INVARIANT 2 upheld: low bits of keys[idx] were not 3 and now they are
    // isTombstone == true IFF low bits were 1
    this.size++;
    if (isTombstone) {
      this.tombstoneCount--;
    }
    return null;
  }

  @Override
  public Byte remove(Object key) {
    if (!(key instanceof String)) {
      return null;
    }
    // System.err.println("remove");
    byte[] keyContent = ((String) key).getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    if (idx >= 0) {
      byte result = this.values[idx];
      // removeByIndex condition upheld: readIndex only returns a valid index if (keys[idx] & 3) == 3
      this.removeByIndex(idx);
      return result;
    }
    return null;
  }

  private boolean removeEntry(Map.Entry<?, ?> e) {
    Object key = e.getKey();
    Object value = e.getValue();
    if (!(key instanceof String)) {
      return false;
    }
    if (!(value instanceof Byte)) {
      return false;
    }
    byte[] keyContent = ((String) key).getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    if (idx >= 0 && this.values[idx] == (Byte) value) {
      // removeByIndex condition upheld: readIndex only returns a valid index if (keys[idx] & 3) == 3
      this.removeByIndex(idx);
      return true;
    }
    return false;
  }

  @Override
  public void putAll(Map<? extends String, ? extends Byte> m) {
    for (Map.Entry<? extends String, ? extends Byte> e : m.entrySet()) {
      this.put(e.getKey(), e.getValue());
    }
  }

  @Override
  public void clear() {
    Arrays.fill(this.keys, 0L);
    // INVARIANT 2 upheld
    this.size = 0;
    this.tombstoneCount = 0;
  }

  @Override
  public Set<String> keySet() {
    return new KeySet(this);
  }

  @Override
  public Collection<Byte> values() {
    return new Values(this);
  }

  @Override
  public Set<Entry<String, Byte>> entrySet() {
    return new EntrySet(this);
  }

  @Override
  protected Object clone() throws CloneNotSupportedException {
    // INVARIANT 1 upheld on the clone
    long[] keysClone = new long[this.keys.length];
    byte[] valuesClone = Arrays.copyOf(this.values, this.values.length);
    KeyStorage newKeyStorage = new KeyStorage();
    for (int i = 0; i < this.keys.length; i++) {
      if ((this.keys[i] & 3) == 3) {
        // INVARIANT 2a upheld: equal size, keysClone[i] has low bits == 3 IFF keys[i] does
        keysClone[i] = newKeyStorage.copyFrom(this.keyStorage, this.keys[i]);
      }
      // INVARIANT 2b upheld: zero tombstones, keysClone[i] has low bits == 0 otherwise
    }

    return new CompactStringByteMap(newKeyStorage, keysClone, valuesClone, this.size);
  }

  // start of section adapted from
  // https://github.com/apache/commons-collections/blob/master/src/main/java/org/apache/commons/collections4/map/AbstractHashedMap.java

  protected static class KeySet extends AbstractSet<String> {
    private final CompactStringByteMap owner;
    protected KeySet(final CompactStringByteMap owner) {
      this.owner = owner;
    }

    public final int size() {
      return owner.size;
    }
    public final void clear() {
      owner.clear();
    }
    public final Iterator<String> iterator() {
      return new KeyIterator(owner);
    }
    public final boolean contains(Object o) {
      return owner.containsKey(o);
    }
    public final boolean remove(Object key) {
      return owner.remove(key) != null;
    }

    public final void forEach(Consumer<? super String> action) {
      if (action == null) {
        throw new NullPointerException();
      }
      // int mc = modCount;
      for (int src = 0; src < owner.keys.length; src++) {
        if ((owner.keys[src] & 3) == 3) {
          action.accept(owner.keyStorage.load(owner.keys[src]));
        }
      }
      // if (modCount != mc) {
      //  throw new ConcurrentModificationException();
      // }
    }
  }

  protected static class Values extends AbstractCollection<Byte> {
    private final CompactStringByteMap owner;
    protected Values(final CompactStringByteMap owner) {
      this.owner = owner;
    }

    public final int size() {
      return owner.size;
    }
    public final void clear() {
      owner.clear();
    }
    public final Iterator<Byte> iterator() {
      return new ValueIterator(owner);
    }
    public final boolean contains(Object o) {
      return owner.containsValue(o);
    }

    public final void forEach(Consumer<? super Byte> action) {
      if (action == null) {
        throw new NullPointerException();
      }
      // int mc = modCount;
      for (int src = 0; src < owner.keys.length; src++) {
        if ((owner.keys[src] & 3) == 3) {
          action.accept(owner.values[src]);
        }
      }
      // if (modCount != mc) {
      //  throw new ConcurrentModificationException();
      // }
    }
  }

  protected static class Node implements Map.Entry<String, Byte> {
    private final CompactStringByteMap owner;
    private final long keyRef;
    private int index;
    private int rehashCount;

    protected Node(CompactStringByteMap owner, int index) {
      this.owner = owner;
      this.keyRef = owner.keys[index];
      this.index = index;
      this.rehashCount = owner.rehashCount;
    }

    private int getIndex() {
      if (this.rehashCount == this.owner.rehashCount) {
        return this.index;
      }
      this.index = this.owner.rereadIndex(this.keyRef);
      if (this.index < 0) {
        throw new IllegalStateException("Entry no longer in map");
      }
      this.rehashCount = owner.rehashCount;
      return this.index;
    }

    @Override
    public String getKey() {
      return this.owner.keyStorage.load(this.owner.keys[this.getIndex()]);
    }

    @Override
    public Byte getValue() {
      return this.owner.values[this.getIndex()];
    }

    @Override
    public Byte setValue(Byte value) {
      int index = this.getIndex();
      Byte prev = this.owner.values[index];
      this.owner.values[index] = value;
      return prev;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Map.Entry<?, ?>)) {
        return false;
      }
      Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
      return Objects.equals(this.getKey(), e.getKey()) && Objects.equals(this.getValue(), e.getValue());
    }

    @Override
    public int hashCode() {
      return this.getKey().hashCode() ^ this.getValue().hashCode();
    }
  }

  protected static class EntrySet extends AbstractSet<Map.Entry<String, Byte>> {
    private final CompactStringByteMap owner;
    protected EntrySet(final CompactStringByteMap owner) {
      this.owner = owner;
    }

    public final int size() {
      return owner.size;
    }
    public final void clear() {
      owner.clear();
    }
    public final Iterator<Map.Entry<String, Byte>> iterator() {
      return new EntryIterator(owner);
    }

    public final boolean contains(Object o) {
      if (!(o instanceof Map.Entry<?, ?>)) {
        return false;
      }
      return owner.containsEntry((Map.Entry<?, ?>) o);
    }
    public final boolean remove(Object o) {
      if (o instanceof Map.Entry<?, ?>) {
        return owner.removeEntry((Map.Entry<?, ?>) o);
      }
      return false;
    }
    public final void forEach(Consumer<? super Map.Entry<String, Byte>> action) {
      if (action == null) {
        throw new NullPointerException();
      }
      // int mc = modCount;
      for (int src = 0; src < owner.keys.length; src++) {
        if ((owner.keys[src] & 3) == 3) {
          action.accept(new Node(owner, src));
        }
      }
      // if (modCount != mc) {
      //  throw new ConcurrentModificationException();
      // }
    }
  }

  protected static abstract class HashIterator {
    protected final CompactStringByteMap owner;
    private final int rehashCount;
    private int index;
    private int nextIndex;

    protected HashIterator(final CompactStringByteMap owner) {
      this.owner = owner;
      this.rehashCount = owner.rehashCount;
      this.index = -1;
      this.nextIndex = this.findIndex(0);
    }

    private final int findIndex(int start) {
      if (this.rehashCount != owner.rehashCount) {
        throw new ConcurrentModificationException();
      }
      for (int src = start; src < owner.keys.length; src++) {
        if ((owner.keys[src] & 3) == 3) {
          return src;
        }
      }
      return -1;
    }

    public final boolean hasNext() {
      return nextIndex != -1;
    }

    public final void remove() {
      // if (modCount != expectedModCount) {
      //  throw new ConcurrentModificationException();
      // }
      if (this.index < 0) {
        throw new IllegalStateException();
      }
      owner.removeByIndex(this.index);
    }

    protected int advance() {
      if (this.nextIndex < 0) {
        throw new NoSuchElementException();
      }
      this.index = this.nextIndex;
      this.nextIndex = this.findIndex(this.index + 1);
      return this.index;
    }
  }

  protected static class KeyIterator extends HashIterator implements Iterator<String> {
    protected KeyIterator(final CompactStringByteMap owner) {
      super(owner);
    }
    public final String next() {
      int idx = this.advance();
      return owner.keyStorage.load(owner.keys[idx]);
    }
  }

  protected static class ValueIterator extends HashIterator implements Iterator<Byte> {
    protected ValueIterator(final CompactStringByteMap owner) {
      super(owner);
    }
    public final Byte next() {
      int idx = this.advance();
      return owner.values[idx];
    }
  }

  protected static class EntryIterator extends HashIterator implements Iterator<Map.Entry<String, Byte>> {
    protected EntryIterator(final CompactStringByteMap owner) {
      super(owner);
    }
    public final Map.Entry<String, Byte> next() {
      int idx = this.advance();
      return new Node(owner, idx);
    }
  }

  // end section adapted from
  // https://github.com/apache/commons-collections/blob/master/src/main/java/org/apache/commons/collections4/map/AbstractHashedMap.java

  static int hashBytes(byte[] keyContent) {
    int h = 1;
    for (int offset = keyContent.length - 1; offset >= 0; offset--) {
      h = 31 * h + (int)keyContent[offset];
    }
    // ensure positive number for convenience with modulo operator
    return h & 0x7fff_ffff;
  }

  static int hashBuffer(ByteBuffer buf, int position, int length) {
    int h = 1;
    byte[] arr = buf.array();
    for (int offset = position + length - 1; offset >= position; offset--) {
      h = 31 * h + (int)arr[offset];
    }
    // ensure positive number for convenience with modulo operator
    return h & 0x7fff_ffff;
  }

  private static class KeyStorage {
    private final List<ByteBuffer> buffers;

    private KeyStorage() {
      this.buffers = new ArrayList<>();
      this.buffers.add(ByteBuffer.allocate(BUF_SIZE));
    }

	// bits[63:23] = offset
    //     [22:2]  = length
    //     [2:0]   = tombstone flag and present/empty flag
    private long store(byte[] keyContent) {
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

    private String load(long keyRef) {
      int which = (int) (keyRef >>> (KEY_OFFSET_BITS + KEY_LEN_BITS + 2));
      int offset = (int) ((keyRef >>> (KEY_LEN_BITS + 2)) & KEY_OFFSET_MASK);
      int length = (int) ((keyRef >>> 2) & KEY_LEN_MASK);
      byte[] bufContent = this.buffers.get(which).array();
      return new String(bufContent, offset, length, StandardCharsets.UTF_8);
    }

    private int hashAt(long keyRef) {
      int which = (int) (keyRef >>> (KEY_OFFSET_BITS + KEY_LEN_BITS + 2));
      int offset = (int) ((keyRef >>> (KEY_LEN_BITS + 2)) & KEY_OFFSET_MASK);
      int length = (int) ((keyRef >>> 2) & KEY_LEN_MASK);
      return hashBuffer(this.buffers.get(which), offset, length);
    }

    private boolean equalsAt(long keyRef, byte[] other) {
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

  /** Index of first empty/tombstone slot in quadratic probe starting from hash(keyContent) */
  private int insertionIndex(byte[] keyContent) {
    int h = hashBytes(keyContent) % this.keys.length;
    int distance = 1;
    while ((keys[h] & 3) == 3) {
      h = (h + distance) % this.keys.length;
      distance++;
    }
    return h;
  }

  /** Index of given key array's first empty/tombstone slot in quadratic probe starting from hashAt(keyRef) */
  private int reinsertionIndex(long[] keys, long keyRef) {
    int h = this.keyStorage.hashAt(keyRef) % keys.length;
    int distance = 1;
    while ((keys[h] & 3) == 3) {
      h = (h + distance) % keys.length;
      distance++;
    }
    return h;
  }

  /**
   * Attempts to find index whose stored key equals the given one, using a quadratic probe starting from
   * hash(keyContent).
   *
   * Returns:
   * <ul>
   * <li> {@code index} when key found
   * <li> {@code -index - 1} when an empty slot is found; the index refers to the first tombstone found
   *   if any, otherwise
   *
   */
  private int readIndex(byte[] keyContent) {
    int h = hashBytes(keyContent) % this.keys.length;
    int distance = 1;
    int firstTombstone = -1;
    while ((this.keys[h] & 1) == 1) {
      if ((this.keys[h] & 2) == 0) {
        // Tombstone
        firstTombstone = firstTombstone < 0 ? h : firstTombstone;
        h = (h + distance) % this.keys.length;
        distance++;
        continue;
      }
      // Live entry, (this.keys[h] & 3) == 3
      if (this.keyStorage.equalsAt(this.keys[h], keyContent)) {
        return h;
      }
      h = (h + distance) % this.keys.length;
      distance++;
    }
    if (firstTombstone >= 0) {
      return -firstTombstone - 1;
    }
    return -h - 1;
  }

  // used by Node to refresh its known index on the first access after a rehash
  private int rereadIndex(long keyRef) {
    int h = this.keyStorage.hashAt(keyRef) % keys.length;
    int distance = 1;
    while ((keys[h] & 3) == 3) {
      if (keys[h] == keyRef) {
        return h;
      }
      h = (h + distance) % keys.length;
      distance++;
    }
    return -1;
  }

  /** INVARIANT 2 upheld WHEN this.keys[idx] has low bits == 3 prior to calling */
  private void removeByIndex(int idx) {
    // flip tombstone flag bit
    this.keys[idx] ^= 2;
    this.size--;
    this.tombstoneCount++;
  }

  private void setCapacity(int cap) {
    // System.err.format("%s setCapacity(%d) from (cap=%d,size=%d,dead=%d)\n", this, cap, this.keys.length, this.size, this.tombstoneCount);
    long[] nextKeys = new long[cap];
    byte[] nextValues = new byte[cap];
    for (int src = 0; src < this.keys.length; src++) {
      if ((this.keys[src] & 3) == 3) {
        // INVARIANT 2a upheld: this condition is true for `size` iterations, and each time
        // the keyRef with low bits == 3 is copied to a **different index** in nextKeys
        //   - reinsertionIndex only returns idx with (keys[idx] & 3 != 3)
        int idx = this.reinsertionIndex(nextKeys, this.keys[src]);
        nextKeys[idx] = this.keys[src];
        nextValues[idx] = this.values[src];
      }
      // INVARIANT 2b upheld: other indices in nextKeys are all zero
    }

    this.keys = nextKeys;
    this.values = nextValues;
    this.tombstoneCount = 0;
    this.rehashCount++;
  }
}
