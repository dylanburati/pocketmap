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

class FloatPocketMapTest {
  @Test void testCreateNegativeCapacity() {
    assertThrows(IllegalArgumentException.class, () -> FloatPocketMap.newUtf8(-1));
  }

  // should still be able to insert
  @Test void testCreateZeroCapacity() {
    Map<String, Float> m = FloatPocketMap.newUtf8(0);
    assertNull(m.put("", 5.5f));
    assertTrue(m.containsKey(""));
    assertFalse(m.containsKey("\u001d\r\u0016\u000f\u0004\u001b\u0002"));
  }

  @Test void testInsert() {
    Map<String, Float> m = FloatPocketMap.newUtf8();
    assertEquals(0, m.size());
    assertNull(m.put("a", 5.5f));
    assertEquals(1, m.size());
    assertNull(m.put("b", 6.25f));
    assertEquals(5.5f, m.get("a"));
    assertEquals(6.25f, m.get("b"));
  }

  @Test void testPutAll() {
    Map<String, Float> m = FloatPocketMap.newUtf8();
    assertEquals(0, m.size());
    assertNull(m.put("a", 5.5f));
    assertEquals(1, m.size());
    assertNull(m.put("b", 6.25f));
    Map<String, Float> m2 = FloatPocketMap.newUtf8();
    m2.putAll(m);
    assertEquals(m2.size(), 2);
    assertEquals(5.5f, m.get("a"));
    assertEquals(6.25f, m.get("b"));
  }

  @Test void testInsertLongKeys() {
    Map<String, Float> m = FloatPocketMap.newUtf8();
    StringBuilder bldr = new StringBuilder();
    for (int i = 16; i < 65536; i += 16) {
      bldr.append("0011223344556677");
      assertNull(m.put(bldr.toString(), 5.5f));
    }
    assertEquals(4095, m.size());
    for (int i = 16; i < 65536; i += 16) {
      assertEquals(5.5f, m.get(bldr.substring(0, i)));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {8, 512, 4096})
  void testLotsOfInsertions(int initialCapacity) {
    Map<String, Float> m = FloatPocketMap.newUtf8(initialCapacity);
    IntFunction<Float> toValue = (v) -> (float) v;
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
    Map<String, Float> m = FloatPocketMap.newUtf8();
    assertNull(m.put("a", 5.5f));
    assertEquals(5.5f, m.get("a"));
    assertEquals(5.5f, m.put("a", 6.25f));
    assertEquals(6.25f, m.get("a"));
  }

  @Test void testInsertAndRemoveWithCollisions() {
    Map<String, Float> m = FloatPocketMap.newUtf8();
    assertNull(m.put("a", 5.5f));
    assertEquals(5.5f, m.get("a"));

    assertNull(m.put("%($", 6.25f));
    assertEquals(5.5f, m.get("a"));
    assertEquals(6.25f, m.get("%($"));

    assertNull(m.put("?/4-AW\u0000", 7.125f));
    assertEquals(5.5f, m.get("a"));
    assertEquals(6.25f, m.get("%($"));
    assertEquals(7.125f, m.get("?/4-AW\u0000"));

    assertEquals(5.5f, m.remove("a"));
    assertEquals(6.25f, m.get("%($"));
    assertEquals(7.125f, m.get("?/4-AW\u0000"));

    assertNull(m.put("a", 5.5f));
    assertEquals(5.5f, m.get("a"));
  }

  @Test void testReplace() {
    Map<String, Float> m = FloatPocketMap.newUtf8();
    assertNull(m.replace("a", 5.5f));
    assertFalse(m.containsKey("a"));
    m.put("a", 5.5f);
    assertEquals(5.5f, m.replace("a", 6.25f));
    assertEquals(6.25f, m.get("a"));
  }

  @Test void testIsEmpty() {
    Map<String, Float> m = FloatPocketMap.newUtf8();
    assertTrue(m.isEmpty());
    assertNull(m.put("a", 5.5f));
    assertFalse(m.isEmpty());
    assertEquals(5.5f, m.remove("a"));
    assertTrue(m.isEmpty());
  }

  @Test void testRemove() {
    Map<String, Float> m = FloatPocketMap.newUtf8();
    assertNull(m.put("a", 5.5f));
    assertEquals(5.5f, m.remove("a"));
    assertNull(m.remove("a"));
  }

  @Test void testEmptyIterators() {
    Map<String, Float> m = FloatPocketMap.newUtf8();
    assertFalse(m.keySet().iterator().hasNext());
    assertFalse(m.values().iterator().hasNext());
    assertFalse(m.entrySet().iterator().hasNext());
  }

  @Test void testEntryIterator() {
    Map<String, Float> m = FloatPocketMap.newUtf8(8);
    List<Float> values = Stream.generate(() -> List.of(5.5f, 6.25f, 7.125f, 8.0625f)).limit(8).flatMap(List::stream).collect(Collectors.toList());
    for (Float v : values) {
      String k = Integer.toString(m.size());
      assertNull(m.put(k, v));
    }
    assertEquals(32, m.size());

    long observed = 0;
    for (Entry<String, Float> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals(values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;
    }

    assertEquals(0xFFFF_FFFFL, observed);
  }

  @Test void testEntryIteratorMutating() {
    Map<String, Float> m = FloatPocketMap.newUtf8(8);
    List<Float> values = Stream.generate(() -> List.of(5.5f, 6.25f, 7.125f, 8.0625f)).limit(8).flatMap(List::stream).collect(Collectors.toList());

    for (Float v : values) {
      String k = Integer.toString(m.size());
      assertNull(m.put(k, v));
    }
    assertEquals(32, m.size());

    for (Iterator<Entry<String, Float>> it = m.entrySet().iterator(); it.hasNext(); ) {
      Entry<String, Float> e = it.next();
      int k = Integer.valueOf(e.getKey());
      assertEquals(values.get(k), e.getValue());
      if (k % 2 == 0) {
        it.remove();
      }
    }
    assertEquals(16, m.size());

    long observed = 0;
    for (Entry<String, Float> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals(values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;

      e.setValue(-e.getValue());
    }

    assertEquals(0xAAAA_AAAAL, observed);

    observed = 0;
    for (Entry<String, Float> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals(-values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;
    }

    assertEquals(0xAAAA_AAAAL, observed);
  }

  @Test void testReplaceAll() {
    Map<String, Float> m = FloatPocketMap.newUtf8(8);
    List<Float> values = Stream.generate(() -> List.of(5.5f, 6.25f, 7.125f, 8.0625f)).limit(8).flatMap(List::stream).collect(Collectors.toList());

    for (Float v : values) {
      String k = Integer.toString(m.size());
      assertNull(m.put(k, v));
    }
    assertEquals(32, m.size());

    m.replaceAll((_k, v) -> -v);
    long observed = 0;
    for (Entry<String, Float> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals(-values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;
    }

    assertEquals(0xFFFF_FFFFL, observed);
  }

  @Test void testRemoveEntry() {
    Map<String, Float> m = FloatPocketMap.newUtf8();
    m.put("a", 5.5f);
    assertFalse(m.remove("a", 6.25f));
    assertTrue(m.remove("a", 5.5f));
    assertNull(m.get("a"));
  }

  @Test void testReplaceEntry() {
    Map<String, Float> m = FloatPocketMap.newUtf8();
    m.put("a", 5.5f);
    assertFalse(m.replace("a", 6.25f, 8.0625f));
    assertTrue(m.replace("a", 5.5f, 8.0625f));
    assertEquals(8.0625f, m.get("a"));
  }

  @Test void testEquals() {
    Map<String, Float> m = FloatPocketMap.newUtf8();
    Map<String, Float> m2 = FloatPocketMap.newUtf8();
    assertFalse(m.equals(null));
    m.put("a", 5.5f);
    m.put("bb", 6.25f);
    m.put("ccc", 7.125f);
    m2.put("a", 5.5f);
    m2.put("bb", 6.25f);
    assertFalse(m.equals(m2));
    m2.put("ccc", 7.125f);
    assertTrue(m.equals(m2));
  }

  @Test void testComputeIfs() {
    Map<String, Float> m = FloatPocketMap.newUtf8(8);
    m.putAll(Map.of("a", 5.5f, "bb", 6.25f, "ccc", 7.125f, "dddd", 8.0625f));

    // update
    Float computed = m.computeIfPresent("a", (k, v) -> {
      assertEquals("a", k);
      assertEquals(5.5f, v);
      return -5.5f;
    });
    assertEquals(-5.5f, computed);
    assertEquals(computed, m.get("a"));
    assertEquals(4, m.size());

    // removal
    computed = m.computeIfPresent("bb", (k, v) -> {
      assertEquals("bb", k);
      assertEquals(6.25f, v);
      return null;
    });
    assertNull(computed);
    assertNull(m.get("bb"));
    assertFalse(m.containsKey("bb"));
    assertEquals(3, m.size());

    // removal, not performed
    computed = m.computeIfAbsent("ccc", (k) -> {
      fail("unreachable");
      return 8.0625f;
    });
    assertNull(computed);
    assertEquals(7.125f, m.get("ccc"));
    assertEquals(3, m.size());

    // insert, not performed
    computed = m.computeIfPresent("eeeee", (_k, _v) -> {
      fail("unreachable");
      return 8.0625f;
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
      return 6.25f;
    });
    assertEquals(6.25f, computed);
    assertEquals(6.25f, m.get("eeeee"));
    assertEquals(4, m.size());
  }

  @Test void testCompute() {
    Map<String, Float> m = FloatPocketMap.newUtf8(8);
    m.putAll(Map.of("a", 5.5f, "bb", 6.25f, "ccc", 7.125f, "dddd", 8.0625f));

    // update
    Float computed = m.compute("a", (k, v) -> {
      assertEquals("a", k);
      assertEquals(5.5f, v);
      return -5.5f;
    });
    assertEquals(-5.5f, computed);
    assertEquals(computed, m.get("a"));
    assertEquals(4, m.size());

    // removal
    computed = m.compute("bb", (k, v) -> {
      assertEquals("bb", k);
      assertEquals(6.25f, v);
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
      return 6.25f;
    });
    assertEquals(6.25f, computed);
    assertEquals(6.25f, m.get("eeeee"));
    assertEquals(4, m.size());
  }

  @Test void testMerge() {
    Map<String, Float> m = FloatPocketMap.newUtf8(8);
    m.putAll(Map.of("a", 5.5f, "bb", 6.25f, "ccc", 7.125f));

    BiFunction<Float, Float, Float> remappingFunction = (v1, v2) -> {
      assertEquals(8.0625f, v2);
      return -v1;
    };

    assertEquals(-5.5f, m.merge("a", 8.0625f, remappingFunction));
    assertEquals(-6.25f, m.merge("bb", 8.0625f, remappingFunction));
    assertEquals(-7.125f, m.merge("ccc", 8.0625f, remappingFunction));
    assertEquals(8.0625f, m.merge("dddd", 8.0625f, (_v1, _v2) -> {
      fail("unreachable");
      return 5.5f;
    }));

    assertEquals(-5.5f, m.get("a"));
    assertEquals(-6.25f, m.get("bb"));
    assertEquals(-7.125f, m.get("ccc"));
    assertEquals(8.0625f, m.get("dddd"));

    assertEquals(4, m.size());
    assertNull(m.merge("a", 8.0625f, (_v1, _v2) -> null));
    assertEquals(3, m.size());
    assertNull(m.merge("bb", 8.0625f, (_v1, _v2) -> null));
    assertEquals(2, m.size());
    assertNull(m.merge("ccc", 8.0625f, (_v1, _v2) -> null));
    assertEquals(1, m.size());
    assertNull(m.merge("dddd", 8.0625f, (_v1, _v2) -> null));
    assertTrue(m.isEmpty());
  }
}
