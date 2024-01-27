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

import static dev.dylanburati.pocketmap.Helpers.*;

class PocketMapTest {
  @Test void testCreateNegativeCapacity() {
    assertThrows(IllegalArgumentException.class, () -> PocketMap.<List<Integer>>newUtf8(-1));
  }

  // should still be able to insert
  @Test void testCreateZeroCapacity() {
    Map<String, List<Integer>> m = PocketMap.newUtf8(0);
    assertNull(m.put("", List.of(505, 10)));
    assertTrue(m.containsKey(""));
    assertFalse(m.containsKey("\u001d\r\u0016\u000f\u0004\u001b\u0002"));
  }

  @Test void testInsert() {
    Map<String, List<Integer>> m = PocketMap.newUtf8();
    assertEquals(0, m.size());
    assertNull(m.put("a", List.of(505, 10)));
    assertEquals(1, m.size());
    assertNull(m.put("b", List.of(606, 12)));
    assertEquals(List.of(505, 10), m.get("a"));
    assertEquals(List.of(606, 12), m.get("b"));
  }

  @Test void testPutAll() {
    Map<String, List<Integer>> m = PocketMap.newUtf8();
    assertEquals(0, m.size());
    assertNull(m.put("a", List.of(505, 10)));
    assertEquals(1, m.size());
    assertNull(m.put("b", List.of(606, 12)));
    Map<String, List<Integer>> m2 = PocketMap.newUtf8();
    m2.putAll(m);
    assertEquals(m2.size(), 2);
    assertEquals(List.of(505, 10), m.get("a"));
    assertEquals(List.of(606, 12), m.get("b"));
  }

  @Test void testInsertLongKeys() {
    Map<String, List<Integer>> m = PocketMap.newUtf8();
    StringBuilder bldr = new StringBuilder();
    for (int i = 16; i < 65536; i += 16) {
      bldr.append("0011223344556677");
      assertNull(m.put(bldr.toString(), List.of(505, 10)));
    }
    assertEquals(4095, m.size());
    for (int i = 16; i < 65536; i += 16) {
      assertEquals(List.of(505, 10), m.get(bldr.substring(0, i)));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {8, 512, 4096})
  void testLotsOfInsertions(int initialCapacity) {
    Map<String, List<Integer>> m = PocketMap.newUtf8(initialCapacity);
    IntFunction<List<Integer>> toValue = v -> List.of(v);
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
    Map<String, List<Integer>> m = PocketMap.newUtf8();
    assertNull(m.put("a", List.of(505, 10)));
    assertEquals(List.of(505, 10), m.get("a"));
    assertEquals(List.of(505, 10), m.put("a", List.of(606, 12)));
    assertEquals(List.of(606, 12), m.get("a"));
  }

  @Test void testInsertAndRemoveWithCollisions() {
    Map<String, List<Integer>> m = PocketMap.newUtf8();
    assertNull(m.put("a", List.of(505, 10)));
    assertEquals(List.of(505, 10), m.get("a"));

    assertNull(m.put("%($", List.of(606, 12)));
    assertEquals(List.of(505, 10), m.get("a"));
    assertEquals(List.of(606, 12), m.get("%($"));

    assertNull(m.put("?/4-AW\u0000", List.of(707, 14)));
    assertEquals(List.of(505, 10), m.get("a"));
    assertEquals(List.of(606, 12), m.get("%($"));
    assertEquals(List.of(707, 14), m.get("?/4-AW\u0000"));

    assertEquals(List.of(505, 10), m.remove("a"));
    assertEquals(List.of(606, 12), m.get("%($"));
    assertEquals(List.of(707, 14), m.get("?/4-AW\u0000"));

    assertNull(m.put("a", List.of(505, 10)));
    assertEquals(List.of(505, 10), m.get("a"));
  }

  @Test void testReplace() {
    Map<String, List<Integer>> m = PocketMap.newUtf8();
    assertNull(m.replace("a", List.of(505, 10)));
    assertFalse(m.containsKey("a"));
    m.put("a", List.of(505, 10));
    assertEquals(List.of(505, 10), m.replace("a", List.of(606, 12)));
    assertEquals(List.of(606, 12), m.get("a"));
  }

  @Test void testIsEmpty() {
    Map<String, List<Integer>> m = PocketMap.newUtf8();
    assertTrue(m.isEmpty());
    assertNull(m.put("a", List.of(505, 10)));
    assertFalse(m.isEmpty());
    assertEquals(List.of(505, 10), m.remove("a"));
    assertTrue(m.isEmpty());
  }

  @Test void testRemove() {
    Map<String, List<Integer>> m = PocketMap.newUtf8();
    assertNull(m.put("a", List.of(505, 10)));
    assertEquals(List.of(505, 10), m.remove("a"));
    assertNull(m.remove("a"));
  }

  @Test void testEmptyIterators() {
    Map<String, List<Integer>> m = PocketMap.newUtf8();
    assertFalse(m.keySet().iterator().hasNext());
    assertFalse(m.values().iterator().hasNext());
    assertFalse(m.entrySet().iterator().hasNext());
  }

  @Test void testEntryIterator() {
    Map<String, List<Integer>> m = PocketMap.newUtf8(8);
    List<List<Integer>> values = Stream.generate(() -> List.of(List.of(505, 10), List.of(606, 12), List.of(707, 14), List.of(808, 16))).limit(8).flatMap(List::stream).collect(Collectors.toList());
    for (List<Integer> v : values) {
      String k = Integer.toString(m.size());
      assertNull(m.put(k, v));
    }
    assertEquals(32, m.size());

    long observed = 0;
    for (Entry<String, List<Integer>> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals(values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;
    }

    assertEquals(0xFFFF_FFFFL, observed);
  }

  @Test void testEntryIteratorMutating() {
    Map<String, List<Integer>> m = PocketMap.newUtf8(8);
    List<List<Integer>> values = Stream.generate(() -> List.of(List.of(505, 10), List.of(606, 12), List.of(707, 14), List.of(808, 16))).limit(8).flatMap(List::stream).collect(Collectors.toList());

    for (List<Integer> v : values) {
      String k = Integer.toString(m.size());
      assertNull(m.put(k, v));
    }
    assertEquals(32, m.size());

    for (Iterator<Entry<String, List<Integer>>> it = m.entrySet().iterator(); it.hasNext(); ) {
      Entry<String, List<Integer>> e = it.next();
      int k = Integer.valueOf(e.getKey());
      assertEquals(values.get(k), e.getValue());
      if (k % 2 == 0) {
        it.remove();
      }
    }
    assertEquals(16, m.size());

    long observed = 0;
    for (Entry<String, List<Integer>> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals(values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;

      e.setValue(reversed(e.getValue()));
    }

    assertEquals(0xAAAA_AAAAL, observed);

    observed = 0;
    for (Entry<String, List<Integer>> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals(reversed(values.get(k)), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;
    }

    assertEquals(0xAAAA_AAAAL, observed);
  }

  @Test void testReplaceAll() {
    Map<String, List<Integer>> m = PocketMap.newUtf8(8);
    List<List<Integer>> values = Stream.generate(() -> List.of(List.of(505, 10), List.of(606, 12), List.of(707, 14), List.of(808, 16))).limit(8).flatMap(List::stream).collect(Collectors.toList());

    for (List<Integer> v : values) {
      String k = Integer.toString(m.size());
      assertNull(m.put(k, v));
    }
    assertEquals(32, m.size());

    m.replaceAll((_k, v) -> reversed(v));
    long observed = 0;
    for (Entry<String, List<Integer>> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals(reversed(values.get(k)), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;
    }

    assertEquals(0xFFFF_FFFFL, observed);
  }

  @Test void testRemoveEntry() {
    Map<String, List<Integer>> m = PocketMap.newUtf8();
    m.put("a", List.of(505, 10));
    assertFalse(m.remove("a", List.of(606, 12)));
    assertTrue(m.remove("a", List.of(505, 10)));
    assertNull(m.get("a"));
  }

  @Test void testReplaceEntry() {
    Map<String, List<Integer>> m = PocketMap.newUtf8();
    m.put("a", List.of(505, 10));
    assertFalse(m.replace("a", List.of(606, 12), List.of(808, 16)));
    assertTrue(m.replace("a", List.of(505, 10), List.of(808, 16)));
    assertEquals(List.of(808, 16), m.get("a"));
  }

  @Test void testEquals() {
    Map<String, List<Integer>> m = PocketMap.newUtf8();
    Map<String, List<Integer>> m2 = PocketMap.newUtf8();
    assertFalse(m.equals(null));
    m.put("a", List.of(505, 10));
    m.put("bb", List.of(606, 12));
    m.put("ccc", List.of(707, 14));
    m2.put("a", List.of(505, 10));
    m2.put("bb", List.of(606, 12));
    assertFalse(m.equals(m2));
    m2.put("ccc", List.of(707, 14));
    assertTrue(m.equals(m2));
  }

  @Test void testComputeIfs() {
    Map<String, List<Integer>> m = PocketMap.newUtf8(8);
    m.putAll(Map.of("a", List.of(505, 10), "bb", List.of(606, 12), "ccc", List.of(707, 14), "dddd", List.of(808, 16)));

    // update
    List<Integer> computed = m.computeIfPresent("a", (k, v) -> {
      assertEquals("a", k);
      assertEquals(List.of(505, 10), v);
      return reversed(List.of(505, 10));
    });
    assertEquals(reversed(List.of(505, 10)), computed);
    assertEquals(computed, m.get("a"));
    assertEquals(4, m.size());

    // removal
    computed = m.computeIfPresent("bb", (k, v) -> {
      assertEquals("bb", k);
      assertEquals(List.of(606, 12), v);
      return null;
    });
    assertNull(computed);
    assertNull(m.get("bb"));
    assertFalse(m.containsKey("bb"));
    assertEquals(3, m.size());

    // removal, not performed
    computed = m.computeIfAbsent("ccc", (k) -> {
      fail("unreachable");
      return List.of(808, 16);
    });
    assertNull(computed);
    assertEquals(List.of(707, 14), m.get("ccc"));
    assertEquals(3, m.size());

    // insert, not performed
    computed = m.computeIfPresent("eeeee", (_k, _v) -> {
      fail("unreachable");
      return List.of(808, 16);
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
      return List.of(606, 12);
    });
    assertEquals(List.of(606, 12), computed);
    assertEquals(List.of(606, 12), m.get("eeeee"));
    assertEquals(4, m.size());
  }

  @Test void testCompute() {
    Map<String, List<Integer>> m = PocketMap.newUtf8(8);
    m.putAll(Map.of("a", List.of(505, 10), "bb", List.of(606, 12), "ccc", List.of(707, 14), "dddd", List.of(808, 16)));

    // update
    List<Integer> computed = m.compute("a", (k, v) -> {
      assertEquals("a", k);
      assertEquals(List.of(505, 10), v);
      return reversed(List.of(505, 10));
    });
    assertEquals(reversed(List.of(505, 10)), computed);
    assertEquals(computed, m.get("a"));
    assertEquals(4, m.size());

    // removal
    computed = m.compute("bb", (k, v) -> {
      assertEquals("bb", k);
      assertEquals(List.of(606, 12), v);
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
      return List.of(606, 12);
    });
    assertEquals(List.of(606, 12), computed);
    assertEquals(List.of(606, 12), m.get("eeeee"));
    assertEquals(4, m.size());
  }

  @Test void testMerge() {
    Map<String, List<Integer>> m = PocketMap.newUtf8(8);
    m.putAll(Map.of("a", List.of(505, 10), "bb", List.of(606, 12), "ccc", List.of(707, 14)));

    BiFunction<List<Integer>, List<Integer>, List<Integer>> remappingFunction = (v1, v2) -> {
      assertEquals(List.of(808, 16), v2);
      return reversed(v1);
    };

    assertEquals(reversed(List.of(505, 10)), m.merge("a", List.of(808, 16), remappingFunction));
    assertEquals(reversed(List.of(606, 12)), m.merge("bb", List.of(808, 16), remappingFunction));
    assertEquals(reversed(List.of(707, 14)), m.merge("ccc", List.of(808, 16), remappingFunction));
    assertEquals(List.of(808, 16), m.merge("dddd", List.of(808, 16), (_v1, _v2) -> {
      fail("unreachable");
      return List.of(505, 10);
    }));

    assertEquals(reversed(List.of(505, 10)), m.get("a"));
    assertEquals(reversed(List.of(606, 12)), m.get("bb"));
    assertEquals(reversed(List.of(707, 14)), m.get("ccc"));
    assertEquals(List.of(808, 16), m.get("dddd"));

    assertEquals(4, m.size());
    assertNull(m.merge("a", List.of(808, 16), (_v1, _v2) -> null));
    assertEquals(3, m.size());
    assertNull(m.merge("bb", List.of(808, 16), (_v1, _v2) -> null));
    assertEquals(2, m.size());
    assertNull(m.merge("ccc", List.of(808, 16), (_v1, _v2) -> null));
    assertEquals(1, m.size());
    assertNull(m.merge("dddd", List.of(808, 16), (_v1, _v2) -> null));
    assertTrue(m.isEmpty());
  }
}
