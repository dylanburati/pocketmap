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
import static dev.dylanburati.pocketmap.KeyStorage.*;

/* template(2)! /**\n * Hash map from strings to \(.val.t)s which minimizes memory overhead at large sizes. */ 
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
/* template! public class \(.val.disp)PocketMap\(.val.generic//"") extends AbstractMap<String, \(.val.view)> implements Cloneable { */
public class IntPocketMap extends AbstractMap<String, Integer> implements Cloneable {
  private static final int DEFAULT_CAPACITY = 65536;
  private final Hasher hasher;
  private final KeyStorage keyStorage;
  // INVARIANT 0: keys.length is a power of 2
  // INVARIANT 1: keys.length == values.length
  private long[] keys;
  /* template! private \(.val.t)[] values; */
  private int[] values;

  // INVARIANT 2:
  //  2A: size           == count [k | k in keys, (k & 255) >= 128]
  //  2B: tombstoneCount == count [k | k in keys, (k & 255) == 1]
  //  2C: 0              == count [k | k in keys, (k & 255) in 2..=127]
  private int size;
  private int tombstoneCount;
  private int rehashCount;

  /* template! public \(.val.disp)PocketMap() { */
  public IntPocketMap() {
    this(DEFAULT_CAPACITY);
  }

  /* template! public \(.val.disp)PocketMap(int initialCapacity) { */
  public IntPocketMap(int initialCapacity) {
    this(initialCapacity, DefaultHasher.instance());
  }

  /* template! public \(.val.disp)PocketMap(int initialCapacity, final Hasher hasher) { */
  public IntPocketMap(int initialCapacity, final Hasher hasher) {
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
    /* template! this.values = new \(.val.t)[cap]; */
    this.values = new int[cap];
    // INVARIANT 2 upheld, keys is all zeroes
    this.size = 0;
    this.tombstoneCount = 0;
  } 

  /* template! private \(.val.disp)PocketMap(final KeyStorage keyStorage, long[] keys, \(.val.t)[] values, int size) { */
  private IntPocketMap(final KeyStorage keyStorage, long[] keys, int[] values, int size) {
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
    /* template(3)! \(if .val.object then "" else "if (!(value instanceof \(.val.view))) {\n  return false;\n}" end) */
    if (!(value instanceof Integer)) {
      return false;
    }
    byte[] keyContent = ((String) key).getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    /* template! return idx >= 0 && \([.val.object, "this.values[idx]", "value", .val.view] | equals); */
    return idx >= 0 && this.values[idx] == (Integer) value;
  }

  @Override
  public boolean containsValue(Object value) {
    /* template(3)! \(if .val.object then "" else "if (!(value instanceof \(.val.view))) {\n  return false;\n}" end) */
    if (!(value instanceof Integer)) {
      return false;
    }
    for (int src = 0; src < this.keys.length; src++) {
      /* template! if ((this.keys[src] & ALIVE_FLAG) == ALIVE_FLAG && \([.val.object, "this.values[src]", "value", .val.view] | equals)) { */
      if ((this.keys[src] & ALIVE_FLAG) == ALIVE_FLAG && this.values[src] == (Integer) value) {
        return true;
      }
    }
    return false;
  }
  /* template(0)! \(if .val.object then "@SuppressWarnings(\"unchecked\")\nprivate static <V> V castUnsafe(Object v) {\n  return (V) v;\n}" else "" end) */

  /* template(2)! @Override\npublic \(.val.view) get(Object key) { */
  @Override
  public Integer get(Object key) {
    return this.getOrDefault(key, null);
  }

  /* template(2)! @Override\npublic \(.val.view) getOrDefault(Object key, \(.val.view) defaultValue) { */
  @Override
  public Integer getOrDefault(Object key, Integer defaultValue) {
    if (!(key instanceof String)) {
      return defaultValue;
    }
    byte[] keyContent = ((String) key).getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    if (idx < 0) {
      return defaultValue;
    }
    /* template! return \([.val.object, "this.values[idx]"] | castUnsafe); */
    return this.values[idx];
  }

  /* template(2)! @Override\npublic \(.val.view) put(String key, \(.val.view) value) { */
  @Override
  public Integer put(String key, Integer value) {
    return this.putImpl(key, value, true);
  }

  /* template(2)! @Override\npublic \(.val.view) putIfAbsent(String key, \(.val.view) value) { */
  @Override
  public Integer putIfAbsent(String key, Integer value) {
    return this.putImpl(key, value, false);
  }

  /* template! private \(.val.view) putImpl(String key, \(.val.view) value, boolean shouldReplace) { */
  private Integer putImpl(String key, Integer value, boolean shouldReplace) {
    byte[] keyContent = key.getBytes(StandardCharsets.UTF_8);
    int hash = this.hasher.hashBytes(keyContent);
    int idx = this.readIndex(hash >>> H2_BITS, hash & H2_MASK, keyContent);
    if (idx >= 0) {
      /* template! \(.val.view) prev = \([.val.object, "this.values[idx]"] | castUnsafe); */
      Integer prev = this.values[idx];
      if (shouldReplace) {
        this.values[idx] = value;
      }
      return prev;
    }
    this.insertByIndex(-idx - 1, hash >>> H2_BITS, hash & H2_MASK, keyContent, value);
    return null;
  }

  /* template(2)! @Override\npublic \(.val.view) replace(String key, \(.val.view) value) { */
  @Override
  public Integer replace(String key, Integer value) {
    byte[] keyContent = key.getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    if (idx >= 0) {
      /* template! \(.val.view) prev = \([.val.object, "this.values[idx]"] | castUnsafe); */
      Integer prev = this.values[idx];
      this.values[idx] = value;
      return prev;
    }
    return null;
  }

  /* template(2)! @Override\npublic boolean replace(String key, \(.val.view) oldValue, \(.val.view) newValue) { */
  @Override
  public boolean replace(String key, Integer oldValue, Integer newValue) {
    byte[] keyContent = key.getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    /* template! if (idx >= 0 && \([.val.object, "this.values[idx]", "oldValue", .val.view] | equals)) { */
    if (idx >= 0 && this.values[idx] == (Integer) oldValue) {
      this.values[idx] = newValue;
      return true;
    }
    return false;
  }

  /* template(2)! @Override\npublic \(.val.view) remove(Object key) { */
  @Override
  public Integer remove(Object key) {
    if (!(key instanceof String)) {
      return null;
    }
    byte[] keyContent = ((String) key).getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    if (idx >= 0) {
      /* template! \(.val.view) result = \([.val.object, "this.values[idx]"] | castUnsafe); */
      Integer result = this.values[idx];
      // removeByIndex condition upheld: readIndex only returns a valid index if (keys[idx] & ALIVE_FLAG) == ALIVE_FLAG
      this.removeByIndex(idx);
      return result;
    }
    return null;
  }

  /* template(2)! @Override\npublic boolean remove(Object key, Object value) { */
  @Override
  public boolean remove(Object key, Object value) {
    if (!(key instanceof String)) {
      return false;
    }
    /* template(3)! \(if .val.object then "" else "if (!(value instanceof \(.val.view))) {\n  return false;\n}" end) */
    if (!(value instanceof Integer)) {
      return false;
    }
    byte[] keyContent = ((String) key).getBytes(StandardCharsets.UTF_8);
    int idx = this.readIndex(keyContent);
    /* template! if (idx >= 0 && \([.val.object, "this.values[idx]", "value", .val.view] | equals)) { */
    if (idx >= 0 && this.values[idx] == (Integer) value) {
      // removeByIndex condition upheld: readIndex only returns a valid index if (keys[idx] & ALIVE_FLAG) == ALIVE_FLAG
      this.removeByIndex(idx);
      return true;
    }
    return false;
  }

  /* template(2)! @Override\npublic \(.val.view) computeIfAbsent(String key, Function<? super String, ? extends \(.val.view)> mappingFunction) { */
  @Override
  public Integer computeIfAbsent(String key, Function<? super String, ? extends Integer> mappingFunction) {
    return this.computeImpl(key, (k, _v) -> mappingFunction.apply(k), true, false);
  }

  /* template(2)! @Override\npublic \(.val.view) computeIfPresent(String key, BiFunction<? super String, ? super \(.val.view), ? extends \(.val.view)> remappingFunction) { */
  @Override
  public Integer computeIfPresent(String key, BiFunction<? super String, ? super Integer, ? extends Integer> remappingFunction) {
    return this.computeImpl(key, remappingFunction, false, true);
  }

  /* template(2)! @Override\npublic \(.val.view) compute(String key, BiFunction<? super String, ? super \(.val.view), ? extends \(.val.view)> remappingFunction) { */
  @Override
  public Integer compute(String key, BiFunction<? super String, ? super Integer, ? extends Integer> remappingFunction) {
    return this.computeImpl(key, remappingFunction, true, true);
  }

  /* template! private \(.val.view) computeImpl(String key, BiFunction<? super String, ? super \(.val.view), ? extends \(.val.view)> remappingFunction, boolean shouldInsert, boolean shouldReplace) { */
  private Integer computeImpl(String key, BiFunction<? super String, ? super Integer, ? extends Integer> remappingFunction, boolean shouldInsert, boolean shouldReplace) {
    Objects.requireNonNull(remappingFunction);
    byte[] keyContent = key.getBytes(StandardCharsets.UTF_8);
    int hash = this.hasher.hashBytes(keyContent);
    int idx = this.readIndex(hash >>> H2_BITS, hash & H2_MASK, keyContent);
    if (idx >= 0) {
      /* template! \(.val.view) result = null; */
      Integer result = null;
      if (shouldReplace) {
        /* template! result = remappingFunction.apply(key, \([.val.object, "this.values[idx]"] | castUnsafe)); */
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
    /* template! \(.val.view) value = remappingFunction.apply(key, null); */
    Integer value = remappingFunction.apply(key, null);
    if (value == null) {
      return null;
    }
    this.insertByIndex(-idx - 1, hash >>> H2_BITS, hash & H2_MASK, keyContent, value);
    return value;
  }

  /* template(2)! @Override\npublic \(.val.view) merge(String key, \(.val.view) value, BiFunction<? super \(.val.view), ? super \(.val.view), ? extends \(.val.view)> remappingFunction) { */
  @Override
  public Integer merge(String key, Integer value, BiFunction<? super Integer, ? super Integer, ? extends Integer> remappingFunction) {
    Objects.requireNonNull(remappingFunction);
    Objects.requireNonNull(value);
    byte[] keyContent = key.getBytes(StandardCharsets.UTF_8);
    int hash = this.hasher.hashBytes(keyContent);
    int idx = this.readIndex(hash >>> H2_BITS, hash & H2_MASK, keyContent);
    if (idx >= 0) {
      /* template! \(.val.view) result = remappingFunction.apply(\([.val.object, "this.values[idx]"] | castUnsafe), value); */
      Integer result = remappingFunction.apply(this.values[idx], value);
      if (result != null) {
        this.values[idx] = result;
      } else {
        this.removeByIndex(idx);
      }
      return result;
    }
    this.insertByIndex(-idx - 1, hash >>> H2_BITS, hash & H2_MASK, keyContent, value);
    return value;
  }

  /* template(2)! @Override\npublic void putAll(Map<? extends String, ? extends \(.val.view)> m) { */
  @Override
  public void putAll(Map<? extends String, ? extends Integer> m) {
    /* template! for (Map.Entry<? extends String, ? extends \(.val.view)> e : m.entrySet()) { */
    for (Map.Entry<? extends String, ? extends Integer> e : m.entrySet()) {
      this.put(e.getKey(), e.getValue());
    }
  }

  /* template(2)! @Override\npublic void replaceAll(BiFunction<? super String, ? super \(.val.view), ? extends \(.val.view)> function) { */
  @Override
  public void replaceAll(BiFunction<? super String, ? super Integer, ? extends Integer> function) {
    Objects.requireNonNull(function);
    for (int i = 0; i < this.keys.length; i++) {
      if ((this.keys[i] & ALIVE_FLAG) == ALIVE_FLAG) {
        String k = this.keyStorage.loadAsString(this.keys[i], StandardCharsets.UTF_8);
        /* template! this.values[i] = function.apply(k, \([.val.object, "this.values[i]"] | castUnsafe)); */
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

  /* template(2)! @Override\npublic Collection<\(.val.view)> values() { */
  @Override
  public Collection<Integer> values() {
    /* template! return new Values\(.val.generic_infer//"")(this); */
    return new Values(this);
  }

  /* template(2)! @Override\npublic Set<Entry<String, \(.val.view)>> entrySet() { */
  @Override
  public Set<Entry<String, Integer>> entrySet() {
    /* template! return new EntrySet\(.val.generic_infer//"")(this); */
    return new EntrySet(this);
  }

  @Override
  protected Object clone() throws CloneNotSupportedException {
    // INVARIANT 1 upheld on the clone
    long[] keysClone = new long[this.keys.length];
    /* template! \(.val.t)[] valuesClone = Arrays.copyOf(this.values, this.values.length); */
    int[] valuesClone = Arrays.copyOf(this.values, this.values.length);
    KeyStorage newKeyStorage = new KeyStorage(this.hasher);
    for (int i = 0; i < this.keys.length; i++) {
      if ((this.keys[i] & ALIVE_FLAG) == ALIVE_FLAG) {
        // INVARIANT 2a upheld: equal size, keysClone[i] has low bits == 3 IFF keys[i] does
        keysClone[i] = newKeyStorage.copyFrom(this.keyStorage, this.keys[i]);
      }
      // INVARIANT 2b upheld: zero tombstones, keysClone[i] has low bits == 0 otherwise
    }

    /* template! return new \(.val.disp)PocketMap\(.val.generic_infer//"")(newKeyStorage, keysClone, valuesClone, this.size); */
    return new IntPocketMap(newKeyStorage, keysClone, valuesClone, this.size);
  }

  // start of section adapted from
  // https://github.com/apache/commons-collections/blob/master/src/main/java/org/apache/commons/collections4/map/AbstractHashedMap.java

  protected static class KeySet extends AbstractSet<String> {
    /* template(2)! private final \(.val.disp)PocketMap\(.val.generic_any//"") owner;\nprotected KeySet(final \(.val.disp)PocketMap\(.val.generic_any//"") owner) { */
    private final IntPocketMap owner;
    protected KeySet(final IntPocketMap owner) {
      this.owner = owner;
    }

    public final int size() {
      return owner.size;
    }
    public final void clear() {
      owner.clear();
    }
    public final Iterator<String> iterator() {
      /* template! return new KeyIterator\(.val.generic_infer//"")(owner); */
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
        if ((owner.keys[src] & ALIVE_FLAG) == ALIVE_FLAG) {
          action.accept(owner.keyStorage.loadAsString(owner.keys[src], StandardCharsets.UTF_8));
        }
      }
      // if (modCount != mc) {
      //  throw new ConcurrentModificationException();
      // }
    }
  }

  /* template! protected static class Values\(.val.generic//"") extends AbstractCollection<\(.val.view)> { */
  protected static class Values extends AbstractCollection<Integer> {
    /* template(2)! private final \(.val.disp)PocketMap\(.val.generic//"") owner;\nprotected Values(final \(.val.disp)PocketMap\(.val.generic//"") owner) { */
    private final IntPocketMap owner;
    protected Values(final IntPocketMap owner) {
      this.owner = owner;
    }

    public final int size() {
      return owner.size;
    }
    public final void clear() {
      owner.clear();
    }
    /* template! public final Iterator<\(.val.view)> iterator() { */
    public final Iterator<Integer> iterator() {
      /* template! return new ValueIterator\(.val.generic_infer//"")(owner); */
      return new ValueIterator(owner);
    }
    public final boolean contains(Object o) {
      return owner.containsValue(o);
    }

    /* template! public final void forEach(Consumer<? super \(.val.view)> action) { */
    public final void forEach(Consumer<? super Integer> action) {
      if (action == null) {
        throw new NullPointerException();
      }
      // int mc = modCount;
      for (int src = 0; src < owner.keys.length; src++) {
        if ((owner.keys[src] & ALIVE_FLAG) == ALIVE_FLAG) {
          /* template! action.accept(\([.val.object, "owner.values[src]"] | castUnsafe)); */
          action.accept(owner.values[src]);
        }
      }
      // if (modCount != mc) {
      //  throw new ConcurrentModificationException();
      // }
    }
  }

  /* template! protected static class Node\(.val.generic//"") implements Map.Entry<String, \(.val.view)> { */
  protected static class Node implements Map.Entry<String, Integer> {
    /* template! private final \(.val.disp)PocketMap\(.val.generic//"") owner; */
    private final IntPocketMap owner;
    private final long keyRef;
    private int index;
    private int rehashCount;

    /* template! protected Node(\(.val.disp)PocketMap\(.val.generic//"") owner, int index) { */
    protected Node(IntPocketMap owner, int index) {
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

    /* template(2)! @Override\npublic \(.val.view) getValue() { */
    @Override
    public Integer getValue() {
      /* template! return \([.val.object, "owner.values[this.getIndex()]"] | castUnsafe); */
      return owner.values[this.getIndex()];
    }

    /* template(2)! @Override\npublic \(.val.view) setValue(\(.val.view) value) { */
    @Override
    public Integer setValue(Integer value) {
      int index = this.getIndex();
      /* template! \(.val.view) prev = \([.val.object, "owner.values[index]"] | castUnsafe); */
      Integer prev = owner.values[index];
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

  /* template! protected static class EntrySet\(.val.generic//"") extends AbstractSet<Map.Entry<String, \(.val.view)>> { */
  protected static class EntrySet extends AbstractSet<Map.Entry<String, Integer>> {
    /* template(2)! private final \(.val.disp)PocketMap\(.val.generic//"") owner;\nprotected EntrySet(final \(.val.disp)PocketMap\(.val.generic//"") owner) { */
    private final IntPocketMap owner;
    protected EntrySet(final IntPocketMap owner) {
      this.owner = owner;
    }

    public final int size() {
      return owner.size;
    }
    public final void clear() {
      owner.clear();
    }
    /* template! public final Iterator<Map.Entry<String, \(.val.view)>> iterator() { */
    public final Iterator<Map.Entry<String, Integer>> iterator() {
      /* template! return new EntryIterator\(.val.generic_infer//"")(owner); */
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
    /* template! public final void forEach(Consumer<? super Map.Entry<String, \(.val.view)>> action) { */
    public final void forEach(Consumer<? super Map.Entry<String, Integer>> action) {
      if (action == null) {
        throw new NullPointerException();
      }
      // int mc = modCount;
      for (int src = 0; src < owner.keys.length; src++) {
        if ((owner.keys[src] & ALIVE_FLAG) == ALIVE_FLAG) {
          /* template! action.accept(new Node\(.val.generic_infer//"")(owner, src)); */
          action.accept(new Node(owner, src));
        }
      }
      // if (modCount != mc) {
      //  throw new ConcurrentModificationException();
      // }
    }
  }

  /* template! protected static abstract class HashIterator\(.val.generic//"") { */
  protected static abstract class HashIterator {
    /* template! protected final \(.val.disp)PocketMap\(.val.generic//"") owner; */
    protected final IntPocketMap owner;
    private final int rehashCount;
    private int index;
    private int nextIndex;

    /* template! protected HashIterator(final \(.val.disp)PocketMap\(.val.generic//"") owner) { */
    protected HashIterator(final IntPocketMap owner) {
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

  /* template! protected static class KeyIterator\(.val.generic//"") extends HashIterator\(.val.generic//"") implements Iterator<String> { */
  protected static class KeyIterator extends HashIterator implements Iterator<String> {
    /* template! protected KeyIterator(final \(.val.disp)PocketMap\(.val.generic//"") owner) { */
    protected KeyIterator(final IntPocketMap owner) {
      super(owner);
    }
    public final String next() {
      int idx = this.advance();
      return owner.keyStorage.loadAsString(owner.keys[idx], StandardCharsets.UTF_8);
    }
  }

  /* template! protected static class ValueIterator\(.val.generic//"") extends HashIterator\(.val.generic//"") implements Iterator<\(.val.view)> { */
  protected static class ValueIterator extends HashIterator implements Iterator<Integer> {
    /* template! protected ValueIterator(final \(.val.disp)PocketMap\(.val.generic//"") owner) { */
    protected ValueIterator(final IntPocketMap owner) {
      super(owner);
    }
    /* template! public final \(.val.view) next() { */
    public final Integer next() {
      int idx = this.advance();
      /* template! return \([.val.object, "owner.values[idx]"] | castUnsafe); */
      return owner.values[idx];
    }
  }

  /* template! protected static class EntryIterator\(.val.generic//"") extends HashIterator\(.val.generic//"") implements Iterator<Map.Entry<String, \(.val.view)>> { */
  protected static class EntryIterator extends HashIterator implements Iterator<Map.Entry<String, Integer>> {
    /* template! protected EntryIterator(final \(.val.disp)PocketMap\(.val.generic//"") owner) { */
    protected EntryIterator(final IntPocketMap owner) {
      super(owner);
    }
    /* template! public final Map.Entry<String, \(.val.view)> next() { */
    public final Map.Entry<String, Integer> next() {
      int idx = this.advance();
      /* template! return new Node\(.val.generic_infer//"")(owner, idx); */
      return new Node(owner, idx);
    }
  }

  // end section adapted from
  // https://github.com/apache/commons-collections/blob/master/src/main/java/org/apache/commons/collections4/map/AbstractHashedMap.java

  /** Index of first empty/tombstone slot in linear probe starting from hash(keyContent) */
  private int insertionIndex(long[] keys, int hashUpper) {
    int h = hashUpper & (keys.length - 1);
    while ((keys[h] & ALIVE_FLAG) == ALIVE_FLAG) {
      h = (h + 1) & (keys.length - 1);
    }
    return h;
  }

  /**
   * Attempts to find index whose stored key equals the given one, using a linear probe starting from 
   * hash(keyContent).
   *
   * Returns:
   * <ul>
   * <li> {@code index} when key found
   * <li> {@code -index - 1} when an empty slot is found; the index refers to the first tombstone found
   *   if any, otherwise the empty slot
   */
  private int readIndex(int hashUpper, int hashLower, byte[] keyContent) {
    int h = hashUpper & (this.keys.length - 1);
    int firstTombstone = -1;
    while ((this.keys[h] & ALIVE_H2_MASK) > 0) {
      if ((this.keys[h] & ALIVE_FLAG) == 0) {
        // Tombstone
        firstTombstone = firstTombstone < 0 ? h : firstTombstone;
        h = (h + 1) & (this.keys.length - 1);
        continue;
      }
      if ((this.keys[h] & H2_MASK) == hashLower && this.keyStorage.equalsAt(this.keys[h], keyContent)) {
        return h;
      }
      h = (h + 1) & (this.keys.length - 1);
    }
    if (firstTombstone >= 0) {
      return -firstTombstone - 1;
    }
    return -h - 1;
  }

  private int readIndex(byte[] keyContent) {
    int hash = this.hasher.hashBytes(keyContent);
    return this.readIndex(hash >>> H2_BITS, hash & H2_MASK, keyContent);
  }

  // used by Node to refresh its known index on the first access after a rehash
  private int rereadIndex(long keyRef) {
    int hash = this.keyStorage.hashAt(keyRef);
    int h = (hash >>> H2_BITS) & (keys.length - 1);
    while ((keys[h] & ALIVE_FLAG) == ALIVE_FLAG) {
      if (keys[h] == keyRef) {
        return h;
      }
      h = (h + 1) & (keys.length - 1);
    }
    return -1;
  }

  /**
   * INVARIANT 2 upheld WHEN this.keys[idx] has low bits != 3 prior to calling 
   *
   * {@code idx} is not guaranteed to be the real insertion index, as it is recalculated if 
   * we resize or purge tombstones.
   */
  /* template! private void insertByIndex(int idx, int hashUpper, int hashLower, byte[] keyContent, \(.val.t) value) { */
  private void insertByIndex(int idx, int hashUpper, int hashLower, byte[] keyContent, int value) {
    boolean isTombstone = (this.keys[idx] & 1) == 1;
    if (!isTombstone && this.maybeSetCapacity()) {
      idx = this.insertionIndex(this.keys, hashUpper);
      isTombstone = false;  // no tombstones following resize
    }
    long keyRef = this.keyStorage.store(keyContent, hashLower);
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
    /* template! \(if .val.object then "" else "// " end)this.values[idx] = null; */
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
    /* template! \(.val.t)[] nextValues = new \(.val.t)[cap]; */
    int[] nextValues = new int[cap];
    for (int src = 0; src < this.keys.length; src++) {
      if ((this.keys[src] & ALIVE_FLAG) == ALIVE_FLAG) {
        // INVARIANT 2a upheld: this condition is true for `size` iterations, and each time
        // the keyRef with ALIVE_FLAG is copied to a **different index** in nextKeys
        //   - insertionIndex only returns idx with (keys[idx] & ALIVE_FLAG) == 0
        int hash = this.keyStorage.hashAt(this.keys[src]);
        int idx = this.insertionIndex(nextKeys, hash >>> H2_BITS);
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
