package dev.dylanburati.pocketmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// no extra imports

class BooleanPocketMapTest {
  @Test void testCreateNegativeCapacity() {
    assertThrows(IllegalArgumentException.class, () -> new BooleanPocketMap(-1));
  }

  // should still be able to insert
  @Test void testCreateZeroCapacity() {
    BooleanPocketMap m = new BooleanPocketMap(0);
    assertNull(m.put("", false));
    assertTrue(m.containsKey(""));
    assertFalse(m.containsKey("\u001d\r\u0016\u000f\u0004\u001b\u0002"));
  }

  @Test void testInsert() {
    BooleanPocketMap m = new BooleanPocketMap();
    assertEquals(0, m.size());
    assertNull(m.put("a", false));
    assertEquals(1, m.size());
    assertNull(m.put("b", true));
    assertEquals(false, m.get("a"));
    assertEquals(true, m.get("b"));
  }

  @Test void testPutAll() {
    BooleanPocketMap m = new BooleanPocketMap();
    assertEquals(0, m.size());
    assertNull(m.put("a", false));
    assertEquals(1, m.size());
    assertNull(m.put("b", true));
    BooleanPocketMap m2 = new BooleanPocketMap();
    m2.putAll(m);
    assertEquals(m2.size(), 2);
    assertEquals(false, m.get("a"));
    assertEquals(true, m.get("b"));
  }

  @Test void testInsertLongKeys() {
    BooleanPocketMap m = new BooleanPocketMap();
    StringBuilder bldr = new StringBuilder();
    for (int i = 16; i < 65536; i += 16) {
      bldr.append("0011223344556677");
      assertNull(m.put(bldr.toString(), false));
    }
    assertEquals(4095, m.size());
    for (int i = 16; i < 65536; i += 16) {
      assertEquals(false, m.get(bldr.substring(0, i)));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {8, 512, 4096})
  void testLotsOfInsertions(int initialCapacity) {
    BooleanPocketMap m = new BooleanPocketMap(initialCapacity);
    IntFunction<Boolean> toValue = (v) -> v % 2 == 0;
    for (int loop = 0; loop < 10; loop++) {
      assertTrue(m.isEmpty());

      int count = 201;
      for (int i = 1; i < count; i++) {
        assertNull(m.put(Integer.toString(i), toValue.apply(i)));

        for (int j = 1; j <= i; j++) {
          assertEquals(toValue.apply(j), m.get(Integer.toString(j)));
        }
        for (int j = i+1; j < count; j++) {
          assertNull(m.get(Integer.toString(j)));
        }
      }

      for (int i = count; i < 2*count; i++) {
        assertNull(m.get(Integer.toString(i)));
      }

      // remove forwards
      for (int i = 1; i < count; i++) {
        assertEquals(toValue.apply(i), m.remove(Integer.toString(i)));

        for (int j = 1; j <= i; j++) {
          assertFalse(m.containsKey(Integer.toString(j)));
        }
        for (int j = i+1; j < count; j++) {
          assertTrue(m.containsKey(Integer.toString(j)));
        }
      }

      for (int i = 1; i < count; i++) {
        assertFalse(m.containsKey(Integer.toString(i)));
      }

      for (int i = 1; i < count; i++) {
        assertNull(m.put(Integer.toString(i), toValue.apply(i)));
      }

      // remove backwards
      for (int i = count - 1; i >= 1; i--) {
        assertEquals(toValue.apply(i), m.remove(Integer.toString(i)));

        for (int j = i; j < count; j++) {
          assertFalse(m.containsKey(Integer.toString(j)));
        }
        for (int j = 1; j < i; j++) {
          assertTrue(m.containsKey(Integer.toString(j)));
        }
      }
    }
  }

  @Test void testInsertOverwrite() {
    BooleanPocketMap m = new BooleanPocketMap();
    assertNull(m.put("a", false));
    assertEquals(false, m.get("a"));
    assertEquals(false, m.put("a", true));
    assertEquals(true, m.get("a"));
  }

  @Test void testInsertAndRemoveWithCollisions() {
    BooleanPocketMap m = new BooleanPocketMap();
    assertNull(m.put("a", false));
    assertEquals(false, m.get("a"));

    assertNull(m.put("%($", true));
    assertEquals(false, m.get("a"));
    assertEquals(true, m.get("%($"));

    assertNull(m.put("?/4-AW\u0000", false));
    assertEquals(false, m.get("a"));
    assertEquals(true, m.get("%($"));
    assertEquals(false, m.get("?/4-AW\u0000"));

    assertEquals(false, m.remove("a"));
    assertEquals(true, m.get("%($"));
    assertEquals(false, m.get("?/4-AW\u0000"));

    assertNull(m.put("a", false));
    assertEquals(false, m.get("a"));
  }

  @Test void testReplace() {
    BooleanPocketMap m = new BooleanPocketMap();
    assertNull(m.replace("a", false));
    assertFalse(m.containsKey("a"));
    m.put("a", false);
    assertEquals(false, m.replace("a", true));
    assertEquals(true, m.get("a"));
  }

  @Test void testIsEmpty() {
    BooleanPocketMap m = new BooleanPocketMap();
    assertTrue(m.isEmpty());
    assertNull(m.put("a", false));
    assertFalse(m.isEmpty());
    assertEquals(false, m.remove("a"));
    assertTrue(m.isEmpty());
  }

  @Test void testRemove() {
    BooleanPocketMap m = new BooleanPocketMap();
    assertNull(m.put("a", false));
    assertEquals(false, m.remove("a"));
    assertNull(m.remove("a"));
  }

  @Test void testEmptyIterators() {
    BooleanPocketMap m = new BooleanPocketMap();
    assertFalse(m.keySet().iterator().hasNext());
    assertFalse(m.values().iterator().hasNext());
    assertFalse(m.entrySet().iterator().hasNext());
  }

  @Test void testEntryIterator() {
    BooleanPocketMap m = new BooleanPocketMap(8);
    List<Boolean> values = Stream.generate(() -> List.of(false, true, false, true)).limit(8).flatMap(List::stream).collect(Collectors.toList());
    for (Boolean v : values) {
      String k = Integer.toString(m.size());
      assertNull(m.put(k, v));
    }
    assertEquals(32, m.size());

    long observed = 0;
    for (Entry<String, Boolean> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals(values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;
    }

    assertEquals(0xFFFF_FFFFL, observed);
  }

  @Test void testEntryIteratorMutating() {
    BooleanPocketMap m = new BooleanPocketMap(8);
    List<Boolean> values = Stream.generate(() -> List.of(false, true, false, true)).limit(8).flatMap(List::stream).collect(Collectors.toList());

    for (Boolean v : values) {
      String k = Integer.toString(m.size());
      assertNull(m.put(k, v));
    }
    assertEquals(32, m.size());

    for (Iterator<Entry<String, Boolean>> it = m.entrySet().iterator(); it.hasNext(); ) {
      Entry<String, Boolean> e = it.next();
      int k = Integer.valueOf(e.getKey());
      assertEquals(values.get(k), e.getValue());
      if (k % 2 == 0) {
        it.remove();
      }
    }
    assertEquals(16, m.size());

    long observed = 0;
    for (Entry<String, Boolean> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals(values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;

      e.setValue(!e.getValue());
    }

    assertEquals(0xAAAA_AAAAL, observed);

    observed = 0;
    for (Entry<String, Boolean> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals(!values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;
    }

    assertEquals(0xAAAA_AAAAL, observed);
  }

  @Test void testReplaceAll() {
    BooleanPocketMap m = new BooleanPocketMap(8);
    List<Boolean> values = Stream.generate(() -> List.of(false, true, false, true)).limit(8).flatMap(List::stream).collect(Collectors.toList());

    for (Boolean v : values) {
      String k = Integer.toString(m.size());
      assertNull(m.put(k, v));
    }
    assertEquals(32, m.size());

    m.replaceAll((_k, v) -> !v);
    long observed = 0;
    for (Entry<String, Boolean> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals(!values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;
    }

    assertEquals(0xFFFF_FFFFL, observed);
  }

  @Test void testRemoveEntry() {
    BooleanPocketMap m = new BooleanPocketMap();
    m.put("a", false);
    assertFalse(m.remove("a", true));
    assertTrue(m.remove("a", false));
    assertNull(m.get("a"));
  }

  @Test void testReplaceEntry() {
    BooleanPocketMap m = new BooleanPocketMap();
    m.put("a", false);
    assertFalse(m.replace("a", true, true));
    assertTrue(m.replace("a", false, true));
    assertEquals(true, m.get("a"));
  }

  @Test void testEquals() {
    BooleanPocketMap m = new BooleanPocketMap();
    BooleanPocketMap m2 = new BooleanPocketMap();
    assertFalse(m.equals(null));
    m.put("a", false);
    m.put("bb", true);
    m.put("ccc", false);
    m2.put("a", false);
    m2.put("bb", true);
    assertFalse(m.equals(m2));
    m2.put("ccc", false);
    assertTrue(m.equals(m2));
  }

  @Test void testComputeIfs() {
    BooleanPocketMap m = new BooleanPocketMap(8);
    m.putAll(Map.of("a", false, "bb", true, "ccc", false, "dddd", true));

    // update
    Boolean computed = m.computeIfPresent("a", (k, v) -> {
      assertEquals("a", k);
      assertEquals(false, v);
      return !false;
    });
    assertEquals(!false, computed);
    assertEquals(computed, m.get("a"));
    assertEquals(4, m.size());

    // removal
    computed = m.computeIfPresent("bb", (k, v) -> {
      assertEquals("bb", k);
      assertEquals(true, v);
      return null;
    });
    assertNull(computed);
    assertNull(m.get("bb"));
    assertFalse(m.containsKey("bb"));
    assertEquals(3, m.size());

    // removal, not performed
    computed = m.computeIfAbsent("ccc", (k) -> {
      fail("unreachable");
      return true;
    });
    assertNull(computed);
    assertEquals(false, m.get("ccc"));
    assertEquals(3, m.size());

    // insert, not performed
    computed = m.computeIfPresent("eeeee", (_k, _v) -> {
      fail("unreachable");
      return true;
    });
    assertNull(computed);
    assertFalse(m.containsKey("eeeee"));
    assertEquals(3, m.size());

    // insert, not performed due to null
    computed = m.computeIfAbsent("eeeee", (k) -> {
      assertEquals("eeeee", k);
      return null;
    });
    assertNull(computed);
    assertFalse(m.containsKey("eeeee"));
    assertEquals(3, m.size());

    // insert
    computed = m.computeIfAbsent("eeeee", (k) -> {
      assertEquals("eeeee", k);
      return true;
    });
    assertEquals(true, computed);
    assertEquals(true, m.get("eeeee"));
    assertEquals(4, m.size());
  }

  @Test void testCompute() {
    BooleanPocketMap m = new BooleanPocketMap(8);
    m.putAll(Map.of("a", false, "bb", true, "ccc", false, "dddd", true));

    // update
    Boolean computed = m.compute("a", (k, v) -> {
      assertEquals("a", k);
      assertEquals(false, v);
      return !false;
    });
    assertEquals(!false, computed);
    assertEquals(computed, m.get("a"));
    assertEquals(4, m.size());

    // removal
    computed = m.compute("bb", (k, v) -> {
      assertEquals("bb", k);
      assertEquals(true, v);
      return null;
    });
    assertNull(computed);
    assertNull(m.get("bb"));
    assertFalse(m.containsKey("bb"));
    assertEquals(3, m.size());

    // insert, not performed due to null
    computed = m.compute("eeeee", (k, v) -> {
      assertEquals("eeeee", k);
      assertNull(v);
      return null;
    });
    assertNull(computed);
    assertFalse(m.containsKey("eeeee"));
    assertEquals(3, m.size());

    // insert
    computed = m.compute("eeeee", (k, v) -> {
      assertEquals("eeeee", k);
      assertNull(v);
      return true;
    });
    assertEquals(true, computed);
    assertEquals(true, m.get("eeeee"));
    assertEquals(4, m.size());
  }

  @Test void testMerge() {
    BooleanPocketMap m = new BooleanPocketMap(8);
    m.putAll(Map.of("a", false, "bb", true, "ccc", false));

    BiFunction<Boolean, Boolean, Boolean> remappingFunction = (v1, v2) -> {
      assertEquals(true, v2);
      return !v1;
    };

    assertEquals(!false, m.merge("a", true, remappingFunction));
    assertEquals(!true, m.merge("bb", true, remappingFunction));
    assertEquals(!false, m.merge("ccc", true, remappingFunction));
    assertEquals(true, m.merge("dddd", true, (_v1, _v2) -> {
      fail("unreachable");
      return false;
    }));

    assertEquals(!false, m.get("a"));
    assertEquals(!true, m.get("bb"));
    assertEquals(!false, m.get("ccc"));
    assertEquals(true, m.get("dddd"));

    assertEquals(4, m.size());
    assertNull(m.merge("a", true, (_v1, _v2) -> null));
    assertEquals(3, m.size());
    assertNull(m.merge("bb", true, (_v1, _v2) -> null));
    assertEquals(2, m.size());
    assertNull(m.merge("ccc", true, (_v1, _v2) -> null));
    assertEquals(1, m.size());
    assertNull(m.merge("dddd", true, (_v1, _v2) -> null));
    assertTrue(m.isEmpty());
  }
}
