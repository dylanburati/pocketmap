package dev.dylanburati.shrinkwrap;

import java.nio.charset.StandardCharsets;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
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
/* template! public class CompactString\(.primitive | pascal)Map extends AbstractMap<String, \(.boxed)> implements Cloneable { */
public class CompactStringIntMap extends AbstractMap<String, Integer> implements Cloneable {
  private static final int DEFAULT_CAPACITY = 65536;
  private final Hasher hasher;
  private final KeyStorage keyStorage;
  // INVARIANT 1: keys.length == values.length
  private long[] keys;
  /* template! private \(.primitive)[] values; */
  private int[] values;

  // INVARIANT 2:
  //  2A: size           == count [k | k in keys, (k & 3) == 3]
  //  2B: tombstoneCount == count [k | k in keys, (k & 3) == 1]
  //  2C: 0              == count [k | k in keys, (k & 3) == 2]
  private int size;
  private int tombstoneCount;
  private int rehashCount;

  /* template! public CompactString\(.primitive | pascal)Map() { */
  public CompactStringIntMap() {
    this(DEFAULT_CAPACITY);
  }

  /* template! public CompactString\(.primitive | pascal)Map(int initialCapacity) { */
  public CompactStringIntMap(int initialCapacity) {
    this(initialCapacity, DefaultHasher.instance());
  }

  /* template! public CompactString\(.primitive | pascal)Map(int initialCapacity, final Hasher hasher) { */
  public CompactStringIntMap(int initialCapacity, final Hasher hasher) {
    if (initialCapacity < 0) {
      throw new IllegalArgumentException("expected non-negative initialCapacity");
    }
    int cap = 8;
    if (initialCapacity > 8) {
      // next power of two >= initialCapacity
      cap = 1 << (32 - Integer.numberOfLeadingZeros(initialCapacity - 1));
    }
    this.hasher = Objects.requireNonNull(hasher);
    this.keyStorage = new KeyStorage(hasher);
    // INVARIANT 1 upheld
    this.keys = new long[cap];
    /* template! this.values = new \(.primitive)[cap]; */
    this.values = new int[cap];
    // INVARIANT 2 upheld, keys is all zeroes
    this.size = 0;
    this.tombstoneCount = 0;
  } 

  /* template! private CompactString\(.primitive | pascal)Map(final KeyStorage keyStorage, long[] keys, \(.primitive)[] values, int size) { */
  private CompactStringIntMap(final KeyStorage keyStorage, long[] keys, int[] values, int size) {
    // clone constructor, invariants are the responsibility of clone()
    this.hasher = keyStorage.hasher;
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
    /* template! if (!(value instanceof \(.boxed))) { */
    if (!(value instanceof Integer)) {
      return false;
    }
    byte[] keyContent = ((String) key).getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    /* template! return idx >= 0 && this.values[idx] == (\(.boxed)) value; */
    return idx >= 0 && this.values[idx] == (Integer) value;
  }

  @Override
  public boolean containsValue(Object value) {
    /* template! if (!(value instanceof \(.boxed))) { */
    if (!(value instanceof Integer)) {
      return false;
    }
    /* template! \(.primitive) needle = (\(.boxed)) value; */
    int needle = (Integer) value;
    for (int src = 0; src < this.keys.length; src++) {
      /* template! if ((this.keys[src] & 3) == 3 && \(if .useEquals then "this.values[src].equals(needle)" else "this.values[src] == needle" end)) { */
      if ((this.keys[src] & 3) == 3 && this.values[src] == needle) {
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
    if (idx < 0) {
      return null;
    }
    return this.values[idx];
  }

  /* template(2)! @Override\npublic \(.boxed) put(String key, \(.boxed) value) { */
  @Override
  public Integer put(String key, Integer value) {
    // System.err.println("put");
    byte[] keyContent = key.getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    if (idx >= 0) {
      /* template! \(.boxed) prev = this.values[idx]; */
      Integer prev = this.values[idx];
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

  /* template(2)! @Override\npublic \(.boxed) remove(Object key) { */
  @Override
  public Integer remove(Object key) {
    if (!(key instanceof String)) {
      return null;
    }
    // System.err.println("remove");
    byte[] keyContent = ((String) key).getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    if (idx >= 0) {
      /* template! \(.primitive) result = this.values[idx]; */
      int result = this.values[idx];
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
    /* template! if (!(value instanceof \(.boxed))) { */
    if (!(value instanceof Integer)) {
      return false;
    }
    byte[] keyContent = ((String) key).getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    /* template! if (idx >= 0 && this.values[idx] == (\(.boxed)) value) { */
    if (idx >= 0 && this.values[idx] == (Integer) value) {
      // removeByIndex condition upheld: readIndex only returns a valid index if (keys[idx] & 3) == 3
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
    // INVARIANT 2 upheld
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

  @Override
  protected Object clone() throws CloneNotSupportedException {
    // INVARIANT 1 upheld on the clone
    long[] keysClone = new long[this.keys.length];
    /* template! \(.primitive)[] valuesClone = Arrays.copyOf(this.values, this.values.length); */
    int[] valuesClone = Arrays.copyOf(this.values, this.values.length);
    KeyStorage newKeyStorage = new KeyStorage(this.hasher);
    for (int i = 0; i < this.keys.length; i++) {
      if ((this.keys[i] & 3) == 3) {
        // INVARIANT 2a upheld: equal size, keysClone[i] has low bits == 3 IFF keys[i] does
        keysClone[i] = newKeyStorage.copyFrom(this.keyStorage, this.keys[i]);
      }
      // INVARIANT 2b upheld: zero tombstones, keysClone[i] has low bits == 0 otherwise
    }

    /* template! return new CompactString\(.primitive | pascal)Map(newKeyStorage, keysClone, valuesClone, this.size); */
    return new CompactStringIntMap(newKeyStorage, keysClone, valuesClone, this.size);
  }

  // start of section adapted from
  // https://github.com/apache/commons-collections/blob/master/src/main/java/org/apache/commons/collections4/map/AbstractHashedMap.java

  protected static class KeySet extends AbstractSet<String> {
    /* template(2)! private final CompactString\(.primitive | pascal)Map owner;\nprotected KeySet(final CompactString\(.primitive | pascal)Map owner) { */
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
        if ((owner.keys[src] & 3) == 3) {
          action.accept(owner.keyStorage.loadAsString(owner.keys[src], StandardCharsets.UTF_8));
        }
      }
      // if (modCount != mc) {
      //  throw new ConcurrentModificationException();
      // }
    }
  }

  /* template! protected static class Values extends AbstractCollection<\(.boxed)> { */
  protected static class Values extends AbstractCollection<Integer> {
    /* template(2)! private final CompactString\(.primitive | pascal)Map owner;\nprotected Values(final CompactString\(.primitive | pascal)Map owner) { */
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
        if ((owner.keys[src] & 3) == 3) {
          action.accept(owner.values[src]);
        }
      }
      // if (modCount != mc) {
      //  throw new ConcurrentModificationException();
      // }
    }
  }

  /* template! protected static class Node implements Map.Entry<String, \(.boxed)> { */
  protected static class Node implements Map.Entry<String, Integer> {
    /* template! private final CompactString\(.primitive | pascal)Map owner; */
    private final CompactStringIntMap owner;
    private final long keyRef;
    private int index;
    private int rehashCount;

    /* template! protected Node(CompactString\(.primitive | pascal)Map owner, int index) { */
    protected Node(CompactStringIntMap owner, int index) {
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
      long keyRef = this.owner.keys[this.getIndex()];
      return this.owner.keyStorage.loadAsString(keyRef, StandardCharsets.UTF_8);
    }

    /* template(2)! @Override\npublic \(.boxed) getValue() { */
    @Override
    public Integer getValue() {
      return this.owner.values[this.getIndex()];
    }

    /* template(2)! @Override\npublic \(.boxed) setValue(\(.boxed) value) { */
    @Override
    public Integer setValue(Integer value) {
      int index = this.getIndex();
      /* template! \(.boxed) prev = this.owner.values[index]; */
      Integer prev = this.owner.values[index];
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

  /* template! protected static class EntrySet extends AbstractSet<Map.Entry<String, \(.boxed)>> { */
  protected static class EntrySet extends AbstractSet<Map.Entry<String, Integer>> {
    /* template(2)! private final CompactString\(.primitive | pascal)Map owner;\nprotected EntrySet(final CompactString\(.primitive | pascal)Map owner) { */
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
    /* template! protected final CompactString\(.primitive | pascal)Map owner; */
    protected final CompactStringIntMap owner;
    private final int rehashCount;
    private int index;
    private int nextIndex;

    /* template! protected HashIterator(final CompactString\(.primitive | pascal)Map owner) { */
    protected HashIterator(final CompactStringIntMap owner) {
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
    /* template! protected KeyIterator(final CompactString\(.primitive | pascal)Map owner) { */
    protected KeyIterator(final CompactStringIntMap owner) {
      super(owner);
    }
    public final String next() {
      int idx = this.advance();
      return owner.keyStorage.loadAsString(owner.keys[idx], StandardCharsets.UTF_8);
    }
  }

  /* template! protected static class ValueIterator extends HashIterator implements Iterator<\(.boxed)> { */
  protected static class ValueIterator extends HashIterator implements Iterator<Integer> {
    /* template! protected ValueIterator(final CompactString\(.primitive | pascal)Map owner) { */
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
    /* template! protected EntryIterator(final CompactString\(.primitive | pascal)Map owner) { */
    protected EntryIterator(final CompactStringIntMap owner) {
      super(owner);
    }
    /* template! public final Map.Entry<String, \(.boxed)> next() { */
    public final Map.Entry<String, Integer> next() {
      int idx = this.advance();
      return new Node(owner, idx);
    }
  }

  // end section adapted from
  // https://github.com/apache/commons-collections/blob/master/src/main/java/org/apache/commons/collections4/map/AbstractHashedMap.java

  /** Index of first empty/tombstone slot in quadratic probe starting from hash(keyContent) */
  private int insertionIndex(byte[] keyContent) {
    int h = (this.hasher.hashBytes(keyContent) & 0x7fff_ffff) % this.keys.length;
    int distance = 1;
    while ((keys[h] & 3) == 3) {
      h = (h + distance) % this.keys.length;
      distance++;
    }
    return h;
  }

  /** Index of given key array's first empty/tombstone slot in quadratic probe starting from hashAt(keyRef) */
  private int reinsertionIndex(long[] keys, long keyRef) {
    int h = (this.keyStorage.hashAt(keyRef) & 0x7fff_ffff) % keys.length;
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
    int h = (this.hasher.hashBytes(keyContent) & 0x7fff_ffff) % this.keys.length;
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
    int h = (this.keyStorage.hashAt(keyRef) & 0x7fff_ffff) % keys.length;
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
    /* template! \(.primitive)[] nextValues = new \(.primitive)[cap]; */
    int[] nextValues = new int[cap];
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
