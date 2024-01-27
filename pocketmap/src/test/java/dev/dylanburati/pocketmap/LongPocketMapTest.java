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

class LongPocketMapTest {
  @Test void testCreateNegativeCapacity() {
    assertThrows(IllegalArgumentException.class, () -> LongPocketMap.newUtf8(-1));
  }

  // should still be able to insert
  @Test void testCreateZeroCapacity() {
    Map<String, Long> m = LongPocketMap.newUtf8(0);
    assertNull(m.put("", 505L));
    assertTrue(m.containsKey(""));
    assertFalse(m.containsKey("\u001d\r\u0016\u000f\u0004\u001b\u0002"));
  }

  @Test void testInsert() {
    Map<String, Long> m = LongPocketMap.newUtf8();
    assertEquals(0, m.size());
    assertNull(m.put("a", 505L));
    assertEquals(1, m.size());
    assertNull(m.put("b", 606L));
    assertEquals(505L, m.get("a"));
    assertEquals(606L, m.get("b"));
  }

  @Test void testPutAll() {
    Map<String, Long> m = LongPocketMap.newUtf8();
    assertEquals(0, m.size());
    assertNull(m.put("a", 505L));
    assertEquals(1, m.size());
    assertNull(m.put("b", 606L));
    Map<String, Long> m2 = LongPocketMap.newUtf8();
    m2.putAll(m);
    assertEquals(m2.size(), 2);
    assertEquals(505L, m.get("a"));
    assertEquals(606L, m.get("b"));
  }

  @Test void testInsertLongKeys() {
    Map<String, Long> m = LongPocketMap.newUtf8();
    StringBuilder bldr = new StringBuilder();
    for (int i = 16; i < 65536; i += 16) {
      bldr.append("0011223344556677");
      assertNull(m.put(bldr.toString(), 505L));
    }
    assertEquals(4095, m.size());
    for (int i = 16; i < 65536; i += 16) {
      assertEquals(505L, m.get(bldr.substring(0, i)));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {8, 512, 4096})
  void testLotsOfInsertions(int initialCapacity) {
    Map<String, Long> m = LongPocketMap.newUtf8(initialCapacity);
    IntFunction<Long> toValue = (v) -> (long) v;
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
    Map<String, Long> m = LongPocketMap.newUtf8();
    assertNull(m.put("a", 505L));
    assertEquals(505L, m.get("a"));
    assertEquals(505L, m.put("a", 606L));
    assertEquals(606L, m.get("a"));
  }

  @Test void testInsertAndRemoveWithCollisions() {
    Map<String, Long> m = LongPocketMap.newUtf8();
    assertNull(m.put("a", 505L));
    assertEquals(505L, m.get("a"));

    assertNull(m.put("%($", 606L));
    assertEquals(505L, m.get("a"));
    assertEquals(606L, m.get("%($"));

    assertNull(m.put("?/4-AW\u0000", 707L));
    assertEquals(505L, m.get("a"));
    assertEquals(606L, m.get("%($"));
    assertEquals(707L, m.get("?/4-AW\u0000"));

    assertEquals(505L, m.remove("a"));
    assertEquals(606L, m.get("%($"));
    assertEquals(707L, m.get("?/4-AW\u0000"));

    assertNull(m.put("a", 505L));
    assertEquals(505L, m.get("a"));
  }

  @Test void testReplace() {
    Map<String, Long> m = LongPocketMap.newUtf8();
    assertNull(m.replace("a", 505L));
    assertFalse(m.containsKey("a"));
    m.put("a", 505L);
    assertEquals(505L, m.replace("a", 606L));
    assertEquals(606L, m.get("a"));
  }

  @Test void testIsEmpty() {
    Map<String, Long> m = LongPocketMap.newUtf8();
    assertTrue(m.isEmpty());
    assertNull(m.put("a", 505L));
    assertFalse(m.isEmpty());
    assertEquals(505L, m.remove("a"));
    assertTrue(m.isEmpty());
  }

  @Test void testRemove() {
    Map<String, Long> m = LongPocketMap.newUtf8();
    assertNull(m.put("a", 505L));
    assertEquals(505L, m.remove("a"));
    assertNull(m.remove("a"));
  }

  @Test void testEmptyIterators() {
    Map<String, Long> m = LongPocketMap.newUtf8();
    assertFalse(m.keySet().iterator().hasNext());
    assertFalse(m.values().iterator().hasNext());
    assertFalse(m.entrySet().iterator().hasNext());
  }

  @Test void testEntryIterator() {
    Map<String, Long> m = LongPocketMap.newUtf8(8);
    List<Long> values = Stream.generate(() -> List.of(505L, 606L, 707L, 808L)).limit(8).flatMap(List::stream).collect(Collectors.toList());
    for (Long v : values) {
      String k = Integer.toString(m.size());
      assertNull(m.put(k, v));
    }
    assertEquals(32, m.size());

    long observed = 0;
    for (Entry<String, Long> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals(values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;
    }

    assertEquals(0xFFFF_FFFFL, observed);
  }

  @Test void testEntryIteratorMutating() {
    Map<String, Long> m = LongPocketMap.newUtf8(8);
    List<Long> values = Stream.generate(() -> List.of(505L, 606L, 707L, 808L)).limit(8).flatMap(List::stream).collect(Collectors.toList());

    for (Long v : values) {
      String k = Integer.toString(m.size());
      assertNull(m.put(k, v));
    }
    assertEquals(32, m.size());

    for (Iterator<Entry<String, Long>> it = m.entrySet().iterator(); it.hasNext(); ) {
      Entry<String, Long> e = it.next();
      int k = Integer.valueOf(e.getKey());
      assertEquals(values.get(k), e.getValue());
      if (k % 2 == 0) {
        it.remove();
      }
    }
    assertEquals(16, m.size());

    long observed = 0;
    for (Entry<String, Long> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals(values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;

      e.setValue(-e.getValue());
    }

    assertEquals(0xAAAA_AAAAL, observed);

    observed = 0;
    for (Entry<String, Long> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals(-values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;
    }

    assertEquals(0xAAAA_AAAAL, observed);
  }

  @Test void testReplaceAll() {
    Map<String, Long> m = LongPocketMap.newUtf8(8);
    List<Long> values = Stream.generate(() -> List.of(505L, 606L, 707L, 808L)).limit(8).flatMap(List::stream).collect(Collectors.toList());

    for (Long v : values) {
      String k = Integer.toString(m.size());
      assertNull(m.put(k, v));
    }
    assertEquals(32, m.size());

    m.replaceAll((_k, v) -> -v);
    long observed = 0;
    for (Entry<String, Long> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals(-values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;
    }

    assertEquals(0xFFFF_FFFFL, observed);
  }

  @Test void testRemoveEntry() {
    Map<String, Long> m = LongPocketMap.newUtf8();
    m.put("a", 505L);
    assertFalse(m.remove("a", 606L));
    assertTrue(m.remove("a", 505L));
    assertNull(m.get("a"));
  }

  @Test void testReplaceEntry() {
    Map<String, Long> m = LongPocketMap.newUtf8();
    m.put("a", 505L);
    assertFalse(m.replace("a", 606L, 808L));
    assertTrue(m.replace("a", 505L, 808L));
    assertEquals(808L, m.get("a"));
  }

  @Test void testEquals() {
    Map<String, Long> m = LongPocketMap.newUtf8();
    Map<String, Long> m2 = LongPocketMap.newUtf8();
    assertFalse(m.equals(null));
    m.put("a", 505L);
    m.put("bb", 606L);
    m.put("ccc", 707L);
    m2.put("a", 505L);
    m2.put("bb", 606L);
    assertFalse(m.equals(m2));
    m2.put("ccc", 707L);
    assertTrue(m.equals(m2));
  }

  @Test void testComputeIfs() {
    Map<String, Long> m = LongPocketMap.newUtf8(8);
    m.putAll(Map.of("a", 505L, "bb", 606L, "ccc", 707L, "dddd", 808L));

    // update
    Long computed = m.computeIfPresent("a", (k, v) -> {
      assertEquals("a", k);
      assertEquals(505L, v);
      return -505L;
    });
    assertEquals(-505L, computed);
    assertEquals(computed, m.get("a"));
    assertEquals(4, m.size());

    // removal
    computed = m.computeIfPresent("bb", (k, v) -> {
      assertEquals("bb", k);
      assertEquals(606L, v);
      return null;
    });
    assertNull(computed);
    assertNull(m.get("bb"));
    assertFalse(m.containsKey("bb"));
    assertEquals(3, m.size());

    // removal, not performed
    computed = m.computeIfAbsent("ccc", (k) -> {
      fail("unreachable");
      return 808L;
    });
    assertNull(computed);
    assertEquals(707L, m.get("ccc"));
    assertEquals(3, m.size());

    // insert, not performed
    computed = m.computeIfPresent("eeeee", (_k, _v) -> {
      fail("unreachable");
      return 808L;
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
      return 606L;
    });
    assertEquals(606L, computed);
    assertEquals(606L, m.get("eeeee"));
    assertEquals(4, m.size());
  }

  @Test void testCompute() {
    Map<String, Long> m = LongPocketMap.newUtf8(8);
    m.putAll(Map.of("a", 505L, "bb", 606L, "ccc", 707L, "dddd", 808L));

    // update
    Long computed = m.compute("a", (k, v) -> {
      assertEquals("a", k);
      assertEquals(505L, v);
      return -505L;
    });
    assertEquals(-505L, computed);
    assertEquals(computed, m.get("a"));
    assertEquals(4, m.size());

    // removal
    computed = m.compute("bb", (k, v) -> {
      assertEquals("bb", k);
      assertEquals(606L, v);
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
      return 606L;
    });
    assertEquals(606L, computed);
    assertEquals(606L, m.get("eeeee"));
    assertEquals(4, m.size());
  }

  @Test void testMerge() {
    Map<String, Long> m = LongPocketMap.newUtf8(8);
    m.putAll(Map.of("a", 505L, "bb", 606L, "ccc", 707L));

    BiFunction<Long, Long, Long> remappingFunction = (v1, v2) -> {
      assertEquals(808L, v2);
      return -v1;
    };

    assertEquals(-505L, m.merge("a", 808L, remappingFunction));
    assertEquals(-606L, m.merge("bb", 808L, remappingFunction));
    assertEquals(-707L, m.merge("ccc", 808L, remappingFunction));
    assertEquals(808L, m.merge("dddd", 808L, (_v1, _v2) -> {
      fail("unreachable");
      return 505L;
    }));

    assertEquals(-505L, m.get("a"));
    assertEquals(-606L, m.get("bb"));
    assertEquals(-707L, m.get("ccc"));
    assertEquals(808L, m.get("dddd"));

    assertEquals(4, m.size());
    assertNull(m.merge("a", 808L, (_v1, _v2) -> null));
    assertEquals(3, m.size());
    assertNull(m.merge("bb", 808L, (_v1, _v2) -> null));
    assertEquals(2, m.size());
    assertNull(m.merge("ccc", 808L, (_v1, _v2) -> null));
    assertEquals(1, m.size());
    assertNull(m.merge("dddd", 808L, (_v1, _v2) -> null));
    assertTrue(m.isEmpty());
  }
}
