package dev.dylanburati.shrinkwrap;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/* template! public class CompactString\(.primitive | pascal) implements Map<String, \(.boxed)> { */
public class CompactStringIntMap implements Map<String, Integer> {
  private static final int BUF_SIZE = 16 * 1024 * 1024;
  private static final int DEFAULT_CAPACITY = 65536;
  private final List<ByteBuffer> keyStorage;
  private long[] keys;
  /* template! private \(.primitive)[] values; */
  private int[] values;
  private int size;
  private int tombstoneCount;

  public CompactStringIntMap() {
    this(DEFAULT_CAPACITY);
  }

  public CompactStringIntMap(int initialCapacity) {
    if (initialCapacity < 0) {
      throw new IllegalArgumentException("expected non-negative initialCapacity");
    }
    int cap = 8;
    if (initialCapacity > 8) {
      // next power of two >= initialCapacity
      cap = 1 << (32 - Integer.numberOfLeadingZeros(initialCapacity - 1));
    }
    this.keyStorage = new ArrayList<>();
    this.keyStorage.add(ByteBuffer.allocate(BUF_SIZE));
    this.keys = new long[cap];
    /* template! this.values = new \(.primitive)[initialCapacity]; */
    this.values = new int[cap];
    this.size = 0;
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
    return this.readIndex(keyContent) != -1;
  }

  public boolean containsEntry(Map.Entry<?, ?> e) {
    Object key = e.getKey();
    Object value = e.getValue();
    if (!(key instanceof String)) {
      return false;
    }
    /* template! if (!(value instanceof \(.boxed))) { */
    if (!(value instanceof Integer)) {
      return false;
    }
    byte[] keyContent = ((String) key).getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    /* template! return idx != -1 && this.values[idx] == (\(.boxed)) value; */
    return idx != -1 && this.values[idx] == (Integer) value;
  }

  @Override
  public boolean containsValue(Object value) {
    /* template! if(!(value instanceof \(.boxed))) { */
    if (!(value instanceof Integer)) {
      return false;
    }
    /* template! \(.primitive) needle = (\(.boxed)) value; */
    int needle = (Integer) value;
    for (int src = 0; src < this.keys.length; src++) {
      /* template! if ((this.keys[src] & 1) == 1 && \(if .useEquals then "this.values[src].equals(needle)" else "this.values[src] == needle" end) */
      if ((this.keys[src] & 1) == 1 && this.values[src] == needle) {
        return true;
      }
    }
    return false;
  }

  /* template(2)! @Override\npublic \(.boxed) get(Object key) { */
  @Override
  public Integer get(Object key) {
    if (!(key instanceof String)) {
      return null;
    }
    byte[] keyContent = ((String) key).getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    if (idx == -1) {
      return null;
    }
    return this.values[idx];
  }

  /* template(2)! @Override\npublic \(.boxed) put(String key, \(.boxed) value) { */
  @Override
  public Integer put(String key, Integer value) {
    byte[] keyContent = key.getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    if (idx != -1) {
      /* template! \(.boxed) prev = this.values[idx]; */
      Integer prev = this.values[idx];
      this.values[idx] = value;
      return prev;
    }

    int cap = this.capacity();
    if (this.size + this.tombstoneCount + 1 > cap * 7 / 8) {
      if (this.size + 1 > cap * 3 / 4) {
        this.setCapacity(cap << 1);
      } else {
        this.setCapacity(cap);
      }
    }
    int h = insertionIndex(this.keys, keyContent);
    long keyRef = this.storeKey(keyContent);
    this.keys[h] = keyRef;
    this.values[h] = value;
    this.size++;
    return null;
  }

  /* template(2)! @Override\npublic \(.boxed) remove(Object key) { */
  @Override
  public Integer remove(Object key) {
    if (!(key instanceof String)) {
      return null;
    }
    byte[] keyContent = ((String) key).getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    if (idx != -1) {
      int result = this.values[idx];
      this.removeByIndex(idx);
      return result;
    }
    return null;
  }

  public boolean removeEntry(Map.Entry<?, ?> e) {
    Object key = e.getKey();
    Object value = e.getValue();
    if (!(key instanceof String)) {
      return false;
    }
    /* template! if (!(value instanceof \(.boxed))) { */
    if (!(value instanceof Integer)) {
      return false;
    }
    byte[] keyContent = ((String) key).getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    /* template! if (idx != -1 && this.values[idx] == (\(.boxed)) value) { */
    if (idx != -1 && this.values[idx] == (Integer) value) {
      this.removeByIndex(idx);
      return true;
    }
    return false;
  }

  /* template(2)! @Override\npublic void putAll(Map<? extends String, ? extends \(.boxed)> m) { */
  @Override
  public void putAll(Map<? extends String, ? extends Integer> m) {
    /* template! for (Map.Entry<? extends String, ? extends \(.boxed)> e : m.entrySet()) { */
    for (Map.Entry<? extends String, ? extends Integer> e : m.entrySet()) {
      this.put(e.getKey(), e.getValue());
    }
  }

  @Override
  public void clear() {
    Arrays.fill(this.keys, 0L);
    this.size = 0;
    this.tombstoneCount = 0;
  }

  @Override
  public Set<String> keySet() {
    return new KeySet(this);
  }

  /* template(2)! @Override\npublic Collection<\(.boxed)> values() { */
  @Override
  public Collection<Integer> values() {
    return new Values(this);
  }

  /* template(2)! @Override\npublic Set<Entry<String, \(.boxed)>> entrySet() { */
  @Override
  public Set<Entry<String, Integer>> entrySet() {
    return new EntrySet(this);
  }

  // start of section adapted from
  // https://github.com/apache/commons-collections/blob/master/src/main/java/org/apache/commons/collections4/map/AbstractHashedMap.java

  protected static class KeySet extends AbstractSet<String> {
    private final CompactStringIntMap owner;

    protected KeySet(final CompactStringIntMap owner) {
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
        if ((owner.keys[src] & 1) == 1) {
          action.accept(owner.loadKey(owner.keys[src]));
        }
      }
      // if (modCount != mc) {
      //  throw new ConcurrentModificationException();
      // }
    }
  }

  /* template! protected static class Values extends AbstractCollection<\(.boxed)> { */
  protected static class Values extends AbstractCollection<Integer> {
    private final CompactStringIntMap owner;

    protected Values(final CompactStringIntMap owner) {
      this.owner = owner;
    }

    public final int size() {
      return owner.size;
    }
    public final void clear() {
      owner.clear();
    }
    /* template! public final Iterator<\(.boxed)> iterator() { */
    public final Iterator<Integer> iterator() {
      return new ValueIterator(owner);
    }
    public final boolean contains(Object o) {
      return owner.containsValue(o);
    }

    /* template! public final void forEach(Consumer<? super \(.boxed)> action) { */
    public final void forEach(Consumer<? super Integer> action) {
      if (action == null) {
        throw new NullPointerException();
      }
      // int mc = modCount;
      for (int src = 0; src < owner.keys.length; src++) {
        if ((owner.keys[src] & 1) == 1) {
          action.accept(owner.values[src]);
        }
      }
      // if (modCount != mc) {
      //  throw new ConcurrentModificationException();
      // }
    }
  }

  /* template! static class Node implements Map.Entry<String, \(.boxed)> { */
  protected static class Node implements Map.Entry<String, Integer> {
    private final CompactStringIntMap owner;
    private final int index;

    Node(CompactStringIntMap owner, int index) {
      this.owner = owner;
      this.index = index;
    }

    @Override
    public String getKey() {
      return this.owner.loadKey(this.owner.keys[this.index]);
    }

    /* template(2)! @Override\npublic \(.boxed) getValue() { */
    @Override
    public Integer getValue() {
      return this.owner.values[this.index];
    }

    /* template(2)! @Override\npublic \(.boxed) setValue(\(.boxed) value) { */
    @Override
    public Integer setValue(Integer value) {
      /* template! \(.boxed) prev = this.owner.values[this.index]; */
      Integer prev = this.owner.values[this.index];
      this.owner.values[this.index] = value;
      return prev;
    }
  }

  /* template! protected static class EntrySet extends AbstractSet<Map.Entry<String, \(.boxed)>> { */
  protected static class EntrySet extends AbstractSet<Map.Entry<String, Integer>> {
    private final CompactStringIntMap owner;

    protected EntrySet(final CompactStringIntMap owner) {
      this.owner = owner;
    }

    public final int size() {
      return owner.size;
    }
    public final void clear() {
      owner.clear();
    }
    /* template! public final Iterator<Map.Entry<String, \(.boxed)>> iterator() { */
    public final Iterator<Map.Entry<String, Integer>> iterator() {
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
    /* template! public final void forEach(Consumer<? super Map.Entry<String, \(.boxed)>> action) { */
    public final void forEach(Consumer<? super Map.Entry<String, Integer>> action) {
      if (action == null) {
        throw new NullPointerException();
      }
      // int mc = modCount;
      for (int src = 0; src < owner.keys.length; src++) {
        if ((owner.keys[src] & 1) == 1) {
          action.accept(new CompactStringIntMap.Node(owner, src));
        }
      }
      // if (modCount != mc) {
      //  throw new ConcurrentModificationException();
      // }
    }
  }

  protected static abstract class HashIterator {
    // private final int expectedModCount;
    protected final CompactStringIntMap owner;
    private int index;
    private int nextIndex;

    protected HashIterator(final CompactStringIntMap owner) {
      // expectedModCount = modCount;
      this.owner = owner;
      this.index = -1;
      this.nextIndex = this.findIndex(0);
    }

    private final int findIndex(int start) {
      for (int src = start; src < owner.keys.length; src++) {
        if ((owner.keys[src] & 1) == 1) {
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
      owner.removeByIndex(this.index);
    }

    protected int advance() {
      if (this.nextIndex < 0) {
        throw new IllegalStateException("iterating past end of CompactStringIntMap");
      }
      this.index = this.nextIndex;
      this.nextIndex = this.findIndex(this.index + 1);
      return this.index;
    }
  }

  protected static class KeyIterator extends HashIterator implements Iterator<String> {
    protected KeyIterator(final CompactStringIntMap owner) {
      super(owner);
    }
    public final String next() {
      int idx = this.advance();
      return owner.loadKey(owner.keys[idx]);
    }
  }

  /* template! protected static class ValueIterator extends HashIterator implements Iterator<\(.boxed)> { */
  protected static class ValueIterator extends HashIterator implements Iterator<Integer> {
    protected ValueIterator(final CompactStringIntMap owner) {
      super(owner);
    }
    /* template! public final \(.boxed) next() { */
    public final Integer next() {
      int idx = this.advance();
      return owner.values[idx];
    }
  }

  /* template! protected static class EntryIterator extends HashIterator implements Iterator<Map.Entry<String, \(.boxed)>> { */
  protected static class EntryIterator extends HashIterator implements Iterator<Map.Entry<String, Integer>> {
    protected EntryIterator(final CompactStringIntMap owner) {
      super(owner);
    }
    /* template! public final Map.Entry<String, \(.boxed)> next() { */
    public final Map.Entry<String, Integer> next() {
      int idx = this.advance();
      return new CompactStringIntMap.Node(owner, idx);
    }
  }

  // end section adapted from
  // https://github.com/apache/commons-collections/blob/master/src/main/java/org/apache/commons/collections4/map/AbstractHashedMap.java

  static int hashBytes(byte[] keyContent) {
    int h = 1;
    for (int offset = keyContent.length - 1; offset >= 0; offset--) {
      h = 31 * h + (int)keyContent[offset];
    }
    return h;
  }

  static int hashBuffer(ByteBuffer buf, int position, int length) {
    int h = 1;
    byte[] arr = buf.array();
    for (int offset = position + length - 1; offset >= position; offset--) {
      h = 31 * h + (int)arr[offset];
    }
    return h;
  }

  private long storeKey(byte[] keyContent) {
    if (keyContent.length > 0xffff) {
      throw new IllegalArgumentException("Key too long");
    }
    int which = this.keyStorage.size() - 1;
    ByteBuffer store = this.keyStorage.get(which);
    if (store.remaining() < keyContent.length) {
      which += 1;
      store = ByteBuffer.allocate(BUF_SIZE);
      this.keyStorage.add(store);
    }
    int offset = store.position();
    store.put(keyContent);
    return ((long) which << 48) | ((long) keyContent.length << 32) | ((long) offset << 1) | 1;
  }

  private String loadKey(long keyRef) {
    int which = (int) (keyRef >> 48);
    int length = (int) ((keyRef >> 32) & 0xffff);
    int offset = (int) ((keyRef >> 1) & 0x7fff_ffff);
    byte[] bufContent = this.keyStorage.get(which).array();
    return new String(bufContent, offset, length, StandardCharsets.UTF_8);
  }

  private int hashBufferByRef(long keyRef) {
    int which = (int) (keyRef >> 48);
    int length = (int) ((keyRef >> 32) & 0xffff);
    int offset = (int) ((keyRef >> 1) & 0x7fff_ffff);
    return hashBuffer(this.keyStorage.get(which), offset, length);
  }

  private boolean equalsBufferByRef(long keyRef, byte[] other) {
    int which = (int) (keyRef >> 48);
    int length = (int) ((keyRef >> 32) & 0xffff);
    int offset = (int) ((keyRef >> 1) & 0x7fff_ffff);
    if (other.length != length) {
      return false;
    }
    byte[] bufContent = this.keyStorage.get(which).array();
    return Arrays.equals(bufContent, offset, offset + length, other, 0, length);
  }

  private static int insertionIndex(long[] keys, byte[] keyContent) {
    // `&` op makes sure this is positive
    int h = hashBytes(keyContent) % keys.length;
    int distance = 1;
    while ((keys[h] & 1) == 1) {
      h = (h + distance) % keys.length;
      distance++;
    }
    return h;
  }

  private int reinsertionIndex(long[] keys, long keyRef) {
    int h = this.hashBufferByRef(keyRef) % keys.length;
    int distance = 1;
    while (keys[h] > 0) {
      h = (h + distance) % keys.length;
      distance++;
    }
    return h;
  }

  private int readIndex(byte[] keyContent) {
    // TODO return (-1 - writeIndex)
    int h = hashBytes(keyContent) % this.capacity();
    int idx = h;
    int distance = 1;
    while (this.keys[idx] > 0) {
      if ((this.keys[idx] & 1) == 0) {
        // Tombstone
        idx = (idx + distance) % this.capacity();
        distance++;
        continue;
      }
      // curr.mark();
      // byte[] dst = new byte[curr.remaining()];
      // curr.get(dst);
      // curr.reset();
      // System.err.format("  [%x]: \"%s\"\n", idx, new String(dst));
      if (this.equalsBufferByRef(this.keys[idx], keyContent)) {
        // System.err.format("  return idx\n");
        return idx;
      }
      idx = (idx + distance) % this.capacity();
      distance++;
    }
    // System.err.format("  return -1\n");
    return -1;
  }

  private void removeByIndex(int idx) {
    // flip lowest bit and leave others alone - lowest bit becomes 0 indicating tombstone
    this.keys[idx] ^= 1;
    this.size--;
    this.tombstoneCount++;
  }

  private int capacity() {
    return this.keys.length;
  }

  private void setCapacity(int cap) {
    System.err.format("%s setCapacity(%d) from (cap=%d,size=%d,dead=%d)\n", this, cap, this.capacity(), this.size, this.tombstoneCount);
    long[] nextKeys = new long[cap];
    /* template! \(.primitive)[] nextValues = new \(.primitive)int[cap]; */
    int[] nextValues = new int[cap];
    for (int src = 0; src < this.keys.length; src++) {
      if ((this.keys[src] & 1) == 1) {
        int idx = this.reinsertionIndex(nextKeys, this.keys[src]);
        nextKeys[idx] = this.keys[src];
        nextValues[idx] = this.values[src];
      }
    }

    this.keys = nextKeys;
    this.values = nextValues;
    this.tombstoneCount = 0;
  }
}
