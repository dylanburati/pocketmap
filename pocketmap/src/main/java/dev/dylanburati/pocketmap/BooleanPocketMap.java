package dev.dylanburati.pocketmap;

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
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Hash map from strings to booleans which minimizes memory overhead at large sizes.
 *
 * Internally, all keys are converted to UTF-8 when inserted, and new keys are
 * pushed into the key storage buffer. Lookups use a {@code long[]} array of
 * references to elements in the storage buffer, and a second primitive array
 * for values. All keys must be smaller than 1048576 bytes.
 *
 * The map doesn't attempt to reclaim the buffer space occupied by deleted keys.
 * To do this manually, clone the map.
 */
public class BooleanPocketMap extends AbstractMap<String, Boolean> implements Cloneable {
  private static final int DEFAULT_CAPACITY = 65536;
  private final Hasher hasher;
  private final KeyStorage keyStorage;
  // INVARIANT 1: keys.length == values.length
  private long[] keys;
  private boolean[] values;

  // INVARIANT 2:
  //  2A: size           == count [k | k in keys, (k & 3) == 3]
  //  2B: tombstoneCount == count [k | k in keys, (k & 3) == 1]
  //  2C: 0              == count [k | k in keys, (k & 3) == 2]
  private int size;
  private int tombstoneCount;
  private int rehashCount;

  public BooleanPocketMap() {
    this(DEFAULT_CAPACITY);
  }

  public BooleanPocketMap(int initialCapacity) {
    this(initialCapacity, DefaultHasher.instance());
  }

  public BooleanPocketMap(int initialCapacity, final Hasher hasher) {
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
    this.values = new boolean[cap];
    // INVARIANT 2 upheld, keys is all zeroes
    this.size = 0;
    this.tombstoneCount = 0;
  }

  private BooleanPocketMap(final KeyStorage keyStorage, long[] keys, boolean[] values, int size) {
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
    if (!(value instanceof Boolean)) {
      return false;
    }
    byte[] keyContent = ((String) key).getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    return idx >= 0 && this.values[idx] == (Boolean) value;
  }

  @Override
  public boolean containsValue(Object value) {
    if (!(value instanceof Boolean)) {
      return false;
    }
    for (int src = 0; src < this.keys.length; src++) {
      if ((this.keys[src] & 3) == 3 && this.values[src] == (Boolean) value) {
        return true;
      }
    }
    return false;
  }

  @Override
  public Boolean get(Object key) {
    return this.getOrDefault(key, null);
  }

  @Override
  public Boolean getOrDefault(Object key, Boolean defaultValue) {
    if (!(key instanceof String)) {
      return defaultValue;
    }
    byte[] keyContent = ((String) key).getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    if (idx < 0) {
      return defaultValue;
    }
    return this.values[idx];
  }

  @Override
  public Boolean put(String key, Boolean value) {
    return this.putImpl(key, value, true);
  }

  @Override
  public Boolean putIfAbsent(String key, Boolean value) {
    return this.putImpl(key, value, false);
  }

  private Boolean putImpl(String key, Boolean value, boolean shouldReplace) {
    byte[] keyContent = key.getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    if (idx >= 0) {
      Boolean prev = this.values[idx];
      if (shouldReplace) {
        this.values[idx] = value;
      }
      return prev;
    }
    this.insertByIndex(-idx - 1, keyContent, value);
    return null;
  }

  @Override
  public Boolean replace(String key, Boolean value) {
    byte[] keyContent = key.getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    if (idx >= 0) {
      Boolean prev = this.values[idx];
      this.values[idx] = value;
      return prev;
    }
    return null;
  }

  @Override
  public boolean replace(String key, Boolean oldValue, Boolean newValue) {
    byte[] keyContent = key.getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    if (idx >= 0 && this.values[idx] == (Boolean) oldValue) {
      this.values[idx] = newValue;
      return true;
    }
    return false;
  }

  @Override
  public Boolean remove(Object key) {
    if (!(key instanceof String)) {
      return null;
    }
    byte[] keyContent = ((String) key).getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    if (idx >= 0) {
      Boolean result = this.values[idx];
      // removeByIndex condition upheld: readIndex only returns a valid index if (keys[idx] & 3) == 3
      this.removeByIndex(idx);
      return result;
    }
    return null;
  }

  @Override
  public boolean remove(Object key, Object value) {
    if (!(key instanceof String)) {
      return false;
    }
    if (!(value instanceof Boolean)) {
      return false;
    }
    byte[] keyContent = ((String) key).getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    if (idx >= 0 && this.values[idx] == (Boolean) value) {
      // removeByIndex condition upheld: readIndex only returns a valid index if (keys[idx] & 3) == 3
      this.removeByIndex(idx);
      return true;
    }
    return false;
  }

  @Override
  public Boolean computeIfAbsent(String key, Function<? super String, ? extends Boolean> mappingFunction) {
    return this.computeImpl(key, (k, _v) -> mappingFunction.apply(k), true, false);
  }

  @Override
  public Boolean computeIfPresent(String key, BiFunction<? super String, ? super Boolean, ? extends Boolean> remappingFunction) {
    return this.computeImpl(key, remappingFunction, false, true);
  }

  @Override
  public Boolean compute(String key, BiFunction<? super String, ? super Boolean, ? extends Boolean> remappingFunction) {
    return this.computeImpl(key, remappingFunction, true, true);
  }

  private Boolean computeImpl(String key, BiFunction<? super String, ? super Boolean, ? extends Boolean> remappingFunction, boolean shouldInsert, boolean shouldReplace) {
    Objects.requireNonNull(remappingFunction);
    byte[] keyContent = key.getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    if (idx >= 0) {
      Boolean result = null;
      if (shouldReplace) {
        result = remappingFunction.apply(key, this.values[idx]);
        if (result != null) {
          this.values[idx] = result;
        } else {
          this.removeByIndex(idx);
        }
      }
      return result;
    }
    if (!shouldInsert) {
      return null;
    }
    Boolean value = remappingFunction.apply(key, null);
    if (value == null) {
      return null;
    }
    this.insertByIndex(-idx - 1, keyContent, value);
    return value;
  }

  @Override
  public Boolean merge(String key, Boolean value, BiFunction<? super Boolean, ? super Boolean, ? extends Boolean> remappingFunction) {
    Objects.requireNonNull(remappingFunction);
    Objects.requireNonNull(value);
    byte[] keyContent = key.getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    if (idx >= 0) {
      Boolean result = remappingFunction.apply(this.values[idx], value);
      if (result != null) {
        this.values[idx] = result;
      } else {
        this.removeByIndex(idx);
      }
      return result;
    }
    this.insertByIndex(-idx - 1, keyContent, value);
    return value;
  }

  @Override
  public void putAll(Map<? extends String, ? extends Boolean> m) {
    for (Map.Entry<? extends String, ? extends Boolean> e : m.entrySet()) {
      this.put(e.getKey(), e.getValue());
    }
  }

  @Override
  public void replaceAll(BiFunction<? super String, ? super Boolean, ? extends Boolean> function) {
    Objects.requireNonNull(function);
    for (int i = 0; i < this.keys.length; i++) {
      if ((this.keys[i] & 3) == 3) {
        String k = this.keyStorage.loadAsString(this.keys[i], StandardCharsets.UTF_8);
        this.values[i] = function.apply(k, this.values[i]);
      }
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
  public Collection<Boolean> values() {
    return new Values(this);
  }

  @Override
  public Set<Entry<String, Boolean>> entrySet() {
    return new EntrySet(this);
  }

  @Override
  protected Object clone() throws CloneNotSupportedException {
    // INVARIANT 1 upheld on the clone
    long[] keysClone = new long[this.keys.length];
    boolean[] valuesClone = Arrays.copyOf(this.values, this.values.length);
    KeyStorage newKeyStorage = new KeyStorage(this.hasher);
    for (int i = 0; i < this.keys.length; i++) {
      if ((this.keys[i] & 3) == 3) {
        // INVARIANT 2a upheld: equal size, keysClone[i] has low bits == 3 IFF keys[i] does
        keysClone[i] = newKeyStorage.copyFrom(this.keyStorage, this.keys[i]);
      }
      // INVARIANT 2b upheld: zero tombstones, keysClone[i] has low bits == 0 otherwise
    }

    return new BooleanPocketMap(newKeyStorage, keysClone, valuesClone, this.size);
  }

  // start of section adapted from
  // https://github.com/apache/commons-collections/blob/master/src/main/java/org/apache/commons/collections4/map/AbstractHashedMap.java

  protected static class KeySet extends AbstractSet<String> {
    private final BooleanPocketMap owner;
    protected KeySet(final BooleanPocketMap owner) {
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

  protected static class Values extends AbstractCollection<Boolean> {
    private final BooleanPocketMap owner;
    protected Values(final BooleanPocketMap owner) {
      this.owner = owner;
    }

    public final int size() {
      return owner.size;
    }
    public final void clear() {
      owner.clear();
    }
    public final Iterator<Boolean> iterator() {
      return new ValueIterator(owner);
    }
    public final boolean contains(Object o) {
      return owner.containsValue(o);
    }

    public final void forEach(Consumer<? super Boolean> action) {
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

  protected static class Node implements Map.Entry<String, Boolean> {
    private final BooleanPocketMap owner;
    private final long keyRef;
    private int index;
    private int rehashCount;

    protected Node(BooleanPocketMap owner, int index) {
      this.owner = owner;
      this.keyRef = owner.keys[index];
      this.index = index;
      this.rehashCount = owner.rehashCount;
    }

    private int getIndex() {
      if (this.rehashCount == owner.rehashCount) {
        return this.index;
      }
      this.index = owner.rereadIndex(this.keyRef);
      if (this.index < 0) {
        throw new IllegalStateException("Entry no longer in map");
      }
      this.rehashCount = owner.rehashCount;
      return this.index;
    }

    @Override
    public String getKey() {
      long keyRef = owner.keys[this.getIndex()];
      return owner.keyStorage.loadAsString(keyRef, StandardCharsets.UTF_8);
    }

    @Override
    public Boolean getValue() {
      return owner.values[this.getIndex()];
    }

    @Override
    public Boolean setValue(Boolean value) {
      int index = this.getIndex();
      Boolean prev = owner.values[index];
      owner.values[index] = value;
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

  protected static class EntrySet extends AbstractSet<Map.Entry<String, Boolean>> {
    private final BooleanPocketMap owner;
    protected EntrySet(final BooleanPocketMap owner) {
      this.owner = owner;
    }

    public final int size() {
      return owner.size;
    }
    public final void clear() {
      owner.clear();
    }
    public final Iterator<Map.Entry<String, Boolean>> iterator() {
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
        Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
        return owner.remove(e.getKey(), e.getValue());
      }
      return false;
    }
    public final void forEach(Consumer<? super Map.Entry<String, Boolean>> action) {
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
    protected final BooleanPocketMap owner;
    private final int rehashCount;
    private int index;
    private int nextIndex;

    protected HashIterator(final BooleanPocketMap owner) {
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
    protected KeyIterator(final BooleanPocketMap owner) {
      super(owner);
    }
    public final String next() {
      int idx = this.advance();
      return owner.keyStorage.loadAsString(owner.keys[idx], StandardCharsets.UTF_8);
    }
  }

  protected static class ValueIterator extends HashIterator implements Iterator<Boolean> {
    protected ValueIterator(final BooleanPocketMap owner) {
      super(owner);
    }
    public final Boolean next() {
      int idx = this.advance();
      return owner.values[idx];
    }
  }

  protected static class EntryIterator extends HashIterator implements Iterator<Map.Entry<String, Boolean>> {
    protected EntryIterator(final BooleanPocketMap owner) {
      super(owner);
    }
    public final Map.Entry<String, Boolean> next() {
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

  /**
   * INVARIANT 2 upheld WHEN this.keys[idx] has low bits != 3 prior to calling
   *
   * {@code idx} is not guaranteed to be the real insertion index, as it is recalculated if
   * we resize or purge tombstones.
   */
  private void insertByIndex(int idx, byte[] keyContent, boolean value) {
    boolean isTombstone = (this.keys[idx] & 1) == 1;
    if (!isTombstone && this.maybeSetCapacity()) {
      idx = this.insertionIndex(keyContent);
      isTombstone = false;  // no tombstones following resize
    }
    long keyRef = this.keyStorage.store(keyContent);
    this.keys[idx] = keyRef;
    this.values[idx] = value;
    this.size++;
    if (isTombstone) {
      this.tombstoneCount--;
    }
  }

  /** INVARIANT 2 upheld WHEN this.keys[idx] has low bits == 3 prior to calling */
  private void removeByIndex(int idx) {
    // flip tombstone flag bit
    this.keys[idx] ^= 2;
    // this.values[idx] = null;
    this.size--;
    this.tombstoneCount++;
  }

  // Called when an insertion to an empty slot is about to happen, returns true if rehashed
  private boolean maybeSetCapacity() {
    int cap = this.keys.length;
    if (this.size + this.tombstoneCount + 1 > cap * 7 / 8) {
      if (this.size + 1 > cap * 3 / 4) {
        this.setCapacity(cap << 1);
      } else {
        this.setCapacity(cap);
      }
      return true;
    }
    return false;
  }

  private void setCapacity(int cap) {
    // System.err.format("%s setCapacity(%d) from (cap=%d,size=%d,dead=%d)\n", this, cap, this.keys.length, this.size, this.tombstoneCount);
    long[] nextKeys = new long[cap];
    boolean[] nextValues = new boolean[cap];
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
