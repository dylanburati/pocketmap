package dev.dylanburati.pocketmap;

import java.nio.charset.Charset;
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
import static dev.dylanburati.pocketmap.KeyStorage.*;

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
public class BooleanPocketMap extends AbstractMap<byte[], Boolean> {
  private static final int DEFAULT_CAPACITY = 65536;
  private final Hasher hasher;
  private final KeyStorage keyStorage;
  // INVARIANT 0: keys.length is a power of 2
  // INVARIANT 1: keys.length == values.length
  private long[] keys;
  private boolean[] values;

  // INVARIANT 2:
  //  2A: size           == count [k | k in keys, (k & 255) >= 128]
  //  2B: tombstoneCount == count [k | k in keys, (k & 255) == 1]
  //  2C: 0              == count [k | k in keys, (k & 255) in 2..=127]
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

  public static StringWrapper newUtf8() {
    return new StringWrapper(new BooleanPocketMap(), StandardCharsets.UTF_8);
  }
  public static StringWrapper newUtf8(int initialCapacity) {
    return new StringWrapper(new BooleanPocketMap(initialCapacity), StandardCharsets.UTF_8);
  }
  public static StringWrapper newUtf8(int initialCapacity, final Hasher hasher) {
    return new StringWrapper(new BooleanPocketMap(initialCapacity, hasher), StandardCharsets.UTF_8);
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
    if (!(key instanceof byte[])) {
      return false;
    }
    return this.readIndex((byte[]) key) >= 0;
  }

  private boolean containsEntry(byte[] key, Boolean value) {
    int idx = this.readIndex(key);
    return idx >= 0 && this.values[idx] == value;
  }

  @Override
  public boolean containsValue(Object value) {
    if (!(value instanceof Boolean)) {
      return false;
    }
    for (int src = 0; src < this.keys.length; src++) {
      if ((this.keys[src] & ALIVE_FLAG) == ALIVE_FLAG && this.values[src] == (Boolean) value) {
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
    if (!(key instanceof byte[])) {
      return defaultValue;
    }
    return this.getImpl((byte[]) key, defaultValue);
  }

  private Boolean getImpl(byte[] key, Boolean defaultValue) {
    int idx = this.readIndex((byte[]) key);
    if (idx < 0) {
      return defaultValue;
    }
    return this.values[idx];
  }

  @Override
  public Boolean put(byte[] key, Boolean value) {
    return this.putImpl(key, value, true);
  }

  @Override
  public Boolean putIfAbsent(byte[] key, Boolean value) {
    return this.putImpl(key, value, false);
  }

  private Boolean putImpl(byte[] key, Boolean value, boolean shouldReplace) {
    int hash = this.hasher.hashBytes(key);
    int idx = this.readIndex(hash, key);
    if (idx >= 0) {
      Boolean prev = this.values[idx];
      if (shouldReplace) {
        this.values[idx] = value;
      }
      return prev;
    }
    this.insertByIndex(-idx - 1, hash, key, value);
    return null;
  }

  @Override
  public Boolean replace(byte[] key, Boolean value) {
    int idx = this.readIndex(key);
    if (idx >= 0) {
      Boolean prev = this.values[idx];
      this.values[idx] = value;
      return prev;
    }
    return null;
  }

  @Override
  public boolean replace(byte[] key, Boolean oldValue, Boolean newValue) {
    int idx = this.readIndex(key);
    if (idx >= 0 && this.values[idx] == (Boolean) oldValue) {
      this.values[idx] = newValue;
      return true;
    }
    return false;
  }

  @Override
  public Boolean remove(Object key) {
    if (!(key instanceof byte[])) {
      return null;
    }
    return this.removeImpl((byte[]) key);
  }

  private Boolean removeImpl(byte[] key) {
    int idx = this.readIndex((byte[]) key);
    if (idx >= 0) {
      Boolean result = this.values[idx];
      // removeByIndex condition upheld: readIndex only returns a valid index if (keys[idx] & ALIVE_FLAG) == ALIVE_FLAG
      this.removeByIndex(idx);
      return result;
    }
    return null;
  }

  @Override
  public boolean remove(Object key, Object value) {
    if (!(key instanceof byte[])) {
      return false;
    }
    if (!(value instanceof Boolean)) {
      return false;
    }
    return this.removeImpl((byte[]) key, (Boolean) value);
  }

  private boolean removeImpl(byte[] key, Boolean value) {
    int idx = this.readIndex(key);
    if (idx >= 0 && this.values[idx] == value) {
      // removeByIndex condition upheld: readIndex only returns a valid index if (keys[idx] & ALIVE_FLAG) == ALIVE_FLAG
      this.removeByIndex(idx);
      return true;
    }
    return false;
  }

  @Override
  public Boolean computeIfAbsent(byte[] key, Function<? super byte[], ? extends Boolean> mappingFunction) {
    return this.computeImpl(key, (k, _v) -> mappingFunction.apply(k), true, false);
  }

  @Override
  public Boolean computeIfPresent(byte[] key, BiFunction<? super byte[], ? super Boolean, ? extends Boolean> remappingFunction) {
    return this.computeImpl(key, remappingFunction, false, true);
  }

  @Override
  public Boolean compute(byte[] key, BiFunction<? super byte[], ? super Boolean, ? extends Boolean> remappingFunction) {
    return this.computeImpl(key, remappingFunction, true, true);
  }

  private Boolean computeImpl(byte[] key, BiFunction<? super byte[], ? super Boolean, ? extends Boolean> remappingFunction, boolean shouldInsert, boolean shouldReplace) {
    Objects.requireNonNull(remappingFunction);
    int hash = this.hasher.hashBytes(key);
    int idx = this.readIndex(hash, key);
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
    this.insertByIndex(-idx - 1, hash, key, value);
    return value;
  }

  @Override
  public Boolean merge(byte[] key, Boolean value, BiFunction<? super Boolean, ? super Boolean, ? extends Boolean> remappingFunction) {
    Objects.requireNonNull(remappingFunction);
    Objects.requireNonNull(value);
    int hash = this.hasher.hashBytes(key);
    int idx = this.readIndex(hash, key);
    if (idx >= 0) {
      Boolean result = remappingFunction.apply(this.values[idx], value);
      if (result != null) {
        this.values[idx] = result;
      } else {
        this.removeByIndex(idx);
      }
      return result;
    }
    this.insertByIndex(-idx - 1, hash, key, value);
    return value;
  }

  @Override
  public void replaceAll(BiFunction<? super byte[], ? super Boolean, ? extends Boolean> function) {
    Objects.requireNonNull(function);
    for (int i = 0; i < this.keys.length; i++) {
      if ((this.keys[i] & ALIVE_FLAG) == ALIVE_FLAG) {
        byte[] k = this.keyStorage.load(this.keys[i]);
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
  public Set<byte[]> keySet() {
    return new KeySet(this);
  }

  @Override
  public Collection<Boolean> values() {
    return new Values(this);
  }

  @Override
  public Set<Entry<byte[], Boolean>> entrySet() {
    return new EntrySet(this);
  }

  /**
   * Creates a shallow clone of this map, with separate key storage.
   */
  public BooleanPocketMap clone() {
    // INVARIANT 1 upheld on the clone
    long[] keysClone = new long[this.keys.length];
    boolean[] valuesClone = Arrays.copyOf(this.values, this.values.length);
    KeyStorage newKeyStorage = new KeyStorage(this.hasher);
    for (int i = 0; i < this.keys.length; i++) {
      if ((this.keys[i] & ALIVE_FLAG) == ALIVE_FLAG) {
        // INVARIANT 2a upheld: equal size, keysClone[i] has low bits == 3 IFF keys[i] does
        keysClone[i] = newKeyStorage.copyFrom(this.keyStorage, this.keys[i]);
      }
      // INVARIANT 2b upheld: zero tombstones, keysClone[i] has low bits == 0 otherwise
    }

    return new BooleanPocketMap(newKeyStorage, keysClone, valuesClone, this.size);
  }

  // start of section adapted from
  // https://github.com/apache/commons-collections/blob/master/src/main/java/org/apache/commons/collections4/map/AbstractHashedMap.java

  protected static class KeySet extends AbstractSet<byte[]> {
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
    public final Iterator<byte[]> iterator() {
      return new KeyIterator(owner);
    }
    public final boolean contains(Object o) {
      return owner.containsKey(o);
    }
    public final boolean remove(Object key) {
      return owner.remove(key) != null;
    }

    public final void forEach(Consumer<? super byte[]> action) {
      if (action == null) {
        throw new NullPointerException();
      }
      // int mc = modCount;
      for (int src = 0; src < owner.keys.length; src++) {
        if ((owner.keys[src] & ALIVE_FLAG) == ALIVE_FLAG) {
          action.accept(owner.keyStorage.load(owner.keys[src]));
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
        if ((owner.keys[src] & ALIVE_FLAG) == ALIVE_FLAG) {
          action.accept(owner.values[src]);
        }
      }
      // if (modCount != mc) {
      //  throw new ConcurrentModificationException();
      // }
    }
  }

  protected static class Node extends NodeImpl implements Map.Entry<byte[], Boolean> {
    protected Node(BooleanPocketMap owner, int index) {
      super(owner, index);
    }

    @Override
    public byte[] getKey() {
      return this.getKeyAsBytes();
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Map.Entry<?, ?>)) {
        return false;
      }
      Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
      return Objects.equals(this.getKey(), e.getKey()) && Objects.equals(this.getValue(), e.getValue());
    }
  }

  protected static class StringWrapperNode extends NodeImpl implements Map.Entry<String, Boolean> {
    private final Charset charset;

    protected StringWrapperNode(final BooleanPocketMap owner, final Charset charset, int index) {
      super(owner, index);
      this.charset = charset;
    }

    @Override
    public String getKey() {
      return this.getKeyAsString(this.charset);
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Map.Entry<?, ?>)) {
        return false;
      }
      Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
      return Objects.equals(this.getKey(), e.getKey()) && Objects.equals(this.getValue(), e.getValue());
    }
  }

  protected static class NodeImpl {
    protected final BooleanPocketMap owner;
    protected final long keyRef;
    private int index;
    private int rehashCount;

    protected NodeImpl(BooleanPocketMap owner, int index) {
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

    protected byte[] getKeyAsBytes() {
      return owner.keyStorage.load(this.keyRef);
    }

    protected String getKeyAsString(Charset charset) {
      return owner.keyStorage.loadAsString(this.keyRef, charset);
    }

    public Boolean getValue() {
      return owner.values[this.getIndex()];
    }

    public Boolean setValue(Boolean value) {
      int index = this.getIndex();
      Boolean prev = owner.values[index];
      owner.values[index] = value;
      return prev;
    }

    @Override
    public int hashCode() {
      return this.getKeyAsBytes().hashCode() ^ this.getValue().hashCode();
    }
  }

  protected static class EntrySet extends AbstractSet<Map.Entry<byte[], Boolean>> {
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
    public final Iterator<Map.Entry<byte[], Boolean>> iterator() {
      return new EntryIterator(owner);
    }

    public final boolean contains(Object o) {
      if (!(o instanceof Map.Entry<?, ?>)) {
        return false;
      }
      Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
      Object key = e.getKey();
      Object value = e.getValue();
      if (!(key instanceof byte[])) {
        return false;
      }
      if (!(value instanceof Boolean)) {
        return false;
      }
      return owner.containsEntry((byte[]) key, (Boolean) value);
    }
    public final boolean remove(Object o) {
      if (o instanceof Map.Entry<?, ?>) {
        Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
        return owner.remove(e.getKey(), e.getValue());
      }
      return false;
    }
    public final void forEach(Consumer<? super Map.Entry<byte[], Boolean>> action) {
      if (action == null) {
        throw new NullPointerException();
      }
      // int mc = modCount;
      for (int src = 0; src < owner.keys.length; src++) {
        if ((owner.keys[src] & ALIVE_FLAG) == ALIVE_FLAG) {
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
        if ((owner.keys[src] & ALIVE_FLAG) == ALIVE_FLAG) {
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

  protected static class KeyIterator extends HashIterator implements Iterator<byte[]> {
    protected KeyIterator(final BooleanPocketMap owner) {
      super(owner);
    }
    public final byte[] next() {
      int idx = this.advance();
      return owner.keyStorage.load(owner.keys[idx]);
    }
  }

  protected static class StringWrapperKeyIterator extends HashIterator implements Iterator<String> {
    private final Charset charset;

    protected StringWrapperKeyIterator(final BooleanPocketMap owner, final Charset charset) {
      super(owner);
      this.charset = charset;
    }
    public final String next() {
      int idx = this.advance();
      return owner.keyStorage.loadAsString(owner.keys[idx], this.charset);
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

  protected static class EntryIterator extends HashIterator implements Iterator<Map.Entry<byte[], Boolean>> {
    protected EntryIterator(final BooleanPocketMap owner) {
      super(owner);
    }
    public final Map.Entry<byte[], Boolean> next() {
      int idx = this.advance();
      return new Node(owner, idx);
    }
  }

  protected static class StringWrapperEntryIterator extends HashIterator implements Iterator<Map.Entry<String, Boolean>> {
    private final Charset charset;

    protected StringWrapperEntryIterator(final BooleanPocketMap owner, final Charset charset) {
      super(owner);
      this.charset = charset;
    }
    public final Map.Entry<String, Boolean> next() {
      int idx = this.advance();
      return new StringWrapperNode(owner, this.charset, idx);
    }
  }

  // end section adapted from
  // https://github.com/apache/commons-collections/blob/master/src/main/java/org/apache/commons/collections4/map/AbstractHashedMap.java

  public static class StringWrapper extends AbstractMap<String, Boolean> {
    protected final BooleanPocketMap inner;
    protected final Charset charset;

    protected StringWrapper(final BooleanPocketMap inner, final Charset charset) {
      this.inner = inner;
      this.charset = charset;
    }

    @Override
    public int size() {
      return inner.size;
    }

    @Override
    public boolean isEmpty() {
      return inner.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
      if (!(key instanceof String)) {
        return false;
      }
      byte[] keyContent = ((String) key).getBytes(this.charset);
      return inner.readIndex(keyContent) >= 0;
    }

    @Override
    public boolean containsValue(Object value) {
      return inner.containsValue(value);
    }

    @Override
    public Boolean get(Object key) {
      if (!(key instanceof String)) {
        return null;
      }
      byte[] keyContent = ((String) key).getBytes(this.charset);
      return inner.getImpl(keyContent, null);
    }

    @Override
    public Boolean getOrDefault(Object key, Boolean defaultValue) {
      if (!(key instanceof String)) {
        return defaultValue;
      }
      byte[] keyContent = ((String) key).getBytes(this.charset);
      return inner.getImpl(keyContent, defaultValue);
    }

    @Override
    public Boolean put(String key, Boolean value) {
      return inner.putImpl(key.getBytes(this.charset), value, true);
    }

    @Override
    public Boolean putIfAbsent(String key, Boolean value) {
      return inner.putImpl(key.getBytes(this.charset), value, false);
    }

    @Override
    public Boolean replace(String key, Boolean value) {
      return inner.replace(key.getBytes(this.charset), value);
    }

    @Override
    public boolean replace(String key, Boolean oldValue, Boolean newValue) {
      return inner.replace(key.getBytes(this.charset), oldValue, newValue);
    }

    @Override
    public Boolean remove(Object key) {
      if (!(key instanceof String)) {
        return null;
      }
      byte[] keyContent = ((String) key).getBytes(this.charset);
      return inner.removeImpl(keyContent);
    }

    @Override
    public boolean remove(Object key, Object value) {
      if (!(key instanceof String)) {
        return false;
      }
      if (!(value instanceof Boolean)) {
        return false;
      }
      byte[] keyContent = ((String) key).getBytes(this.charset);
      return inner.removeImpl(keyContent, (Boolean) value);
    }

    @Override
    public Boolean computeIfAbsent(String key, Function<? super String, ? extends Boolean> mappingFunction) {
      return inner.computeImpl(key.getBytes(this.charset), (_k, _v) -> mappingFunction.apply(key), true, false);
    }

    @Override
    public Boolean computeIfPresent(String key, BiFunction<? super String, ? super Boolean, ? extends Boolean> remappingFunction) {
      return inner.computeImpl(key.getBytes(this.charset), (_k, v) -> remappingFunction.apply(key, v), false, true);
    }

    @Override
    public Boolean compute(String key, BiFunction<? super String, ? super Boolean, ? extends Boolean> remappingFunction) {
      return inner.computeImpl(key.getBytes(this.charset), (_k, v) -> remappingFunction.apply(key, v), true, true);
    }

    @Override
    public Boolean merge(String key, Boolean value, BiFunction<? super Boolean, ? super Boolean, ? extends Boolean> remappingFunction) {
      return inner.merge(key.getBytes(this.charset), value, remappingFunction);
    }

    @Override
    public void replaceAll(BiFunction<? super String, ? super Boolean, ? extends Boolean> function) {
      Objects.requireNonNull(function);
      for (int i = 0; i < inner.keys.length; i++) {
        if ((inner.keys[i] & ALIVE_FLAG) == ALIVE_FLAG) {
          String k = inner.keyStorage.loadAsString(inner.keys[i], this.charset);
          inner.values[i] = function.apply(k, inner.values[i]);
        }
      }
    }

    @Override
    public void clear() {
      inner.clear();
    }

    @Override
    public Set<String> keySet() {
      return new KeySet(this);
    }

    @Override
    public Collection<Boolean> values() {
      return inner.values();
    }

    @Override
    public Set<Entry<String, Boolean>> entrySet() {
      return new EntrySet(this);
    }

    /**
     * Creates a shallow clone of this map, with separate key storage.
     */
    public StringWrapper clone() {
      BooleanPocketMap innerClone = inner.clone();
      return new StringWrapper(innerClone, this.charset);
    }

    protected static class KeySet extends AbstractSet<String> {
      private final StringWrapper owner;
      protected KeySet(final StringWrapper owner) {
        this.owner = owner;
      }

      public final int size() {
        return owner.inner.size;
      }
      public final void clear() {
        owner.clear();
      }
      public final Iterator<String> iterator() {
        return new StringWrapperKeyIterator(owner.inner, owner.charset);
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
        for (int src = 0; src < owner.inner.keys.length; src++) {
          if ((owner.inner.keys[src] & ALIVE_FLAG) == ALIVE_FLAG) {
            action.accept(owner.inner.keyStorage.loadAsString(owner.inner.keys[src], owner.charset));
          }
        }
        // if (modCount != mc) {
        //  throw new ConcurrentModificationException();
        // }
      }
    }

    protected static class EntrySet extends AbstractSet<Map.Entry<String, Boolean>> {
      private final StringWrapper owner;
      protected EntrySet(final StringWrapper owner) {
        this.owner = owner;
      }

      public final int size() {
        return owner.inner.size;
      }
      public final void clear() {
        owner.clear();
      }
      public final Iterator<Map.Entry<String, Boolean>> iterator() {
        return new StringWrapperEntryIterator(owner.inner, owner.charset);
      }

      public final boolean contains(Object o) {
        if (!(o instanceof Map.Entry<?, ?>)) {
          return false;
        }
        Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
        Object key = e.getKey();
        Object value = e.getValue();
        if (!(key instanceof String)) {
          return false;
        }
        if (!(value instanceof Boolean)) {
          return false;
        }
        byte[] keyContent = ((String) key).getBytes(owner.charset);
        return owner.inner.containsEntry(keyContent, (Boolean) value);
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
        for (int src = 0; src < owner.inner.keys.length; src++) {
          if ((owner.inner.keys[src] & ALIVE_FLAG) == ALIVE_FLAG) {
            action.accept(new StringWrapperNode(owner.inner, owner.charset, src));
          }
        }
        // if (modCount != mc) {
        //  throw new ConcurrentModificationException();
        // }
      }
    }
  }

  /** Index of first empty/tombstone slot in quadratic probe starting from hash(keyContent) */
  private int insertionIndex(long[] keys, int hash) {
    int h = hash & (keys.length - 1);
    int distance = 1;
    while ((keys[h] & ALIVE_FLAG) == ALIVE_FLAG) {
      h = (h + distance) & (keys.length - 1);
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
   *   if any, otherwise the empty slot
   */
  private int readIndex(int hash, byte[] keyContent) {
    int h = hash & (this.keys.length - 1);
    int distance = 1;
    int firstTombstone = -1;
    while ((this.keys[h] & ALIVE_H2_MASK) > 0) {
      if ((this.keys[h] & ALIVE_FLAG) == 0) {
        // Tombstone
        firstTombstone = firstTombstone < 0 ? h : firstTombstone;
        h = (h + distance) & (this.keys.length - 1);
        distance++;
        continue;
      }
      if (this.keyStorage.equalsAt(this.keys[h], keyContent)) {
        return h;
      }
      h = (h + distance) & (this.keys.length - 1);
      distance++;
    }
    if (firstTombstone >= 0) {
      return -firstTombstone - 1;
    }
    return -h - 1;
  }

  private int readIndex(byte[] keyContent) {
    int hash = this.hasher.hashBytes(keyContent);
    return this.readIndex(hash, keyContent);
  }

  // used by Node to refresh its known index on the first access after a rehash
  private int rereadIndex(long keyRef) {
    int hash = this.keyStorage.hashAt(keyRef);
    int h = hash & (keys.length - 1);
    int distance = 1;
    while ((keys[h] & ALIVE_FLAG) == ALIVE_FLAG) {
      if (keys[h] == keyRef) {
        return h;
      }
      h = (h + distance) & (keys.length - 1);
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
  private void insertByIndex(int idx, int hash, byte[] keyContent, boolean value) {
    boolean isTombstone = (this.keys[idx] & 1) == 1;
    if (!isTombstone && this.maybeSetCapacity()) {
      idx = this.insertionIndex(this.keys, hash);
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

  /** INVARIANT 2 upheld WHEN this.keys[idx] has ALIVE_FLAG prior to calling */
  private void removeByIndex(int idx) {
    // set alive bit 0, hash to 1 so not treated as empty
    this.keys[idx] ^= (this.keys[idx] ^ 0x01) & ALIVE_H2_MASK;
    // this.values[idx] = null;
    this.size--;
    this.tombstoneCount++;
  }

  // Called when an insertion to an empty slot is about to happen, returns true if rehashed
  private boolean maybeSetCapacity() {
    int cap = this.keys.length;
    if (this.size + this.tombstoneCount + 1 > cap * 7 / 8) {
      // INVARIANT 0 upheld: we either double or remain the same
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
      if ((this.keys[src] & ALIVE_FLAG) == ALIVE_FLAG) {
        // INVARIANT 2a upheld: this condition is true for `size` iterations, and each time
        // the keyRef with ALIVE_FLAG is copied to a **different index** in nextKeys
        //   - insertionIndex only returns idx with (keys[idx] & ALIVE_FLAG) == 0
        int hash = this.keyStorage.hashAt(this.keys[src]);
        int idx = this.insertionIndex(nextKeys, hash);
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
