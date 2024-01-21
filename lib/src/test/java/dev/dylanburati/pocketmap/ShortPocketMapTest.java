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

class ShortPocketMapTest {
  @Test void testCreateNegativeCapacity() {
    assertThrows(IllegalArgumentException.class, () -> new ShortPocketMap(-1));
  }

  // should still be able to insert
  @Test void testCreateZeroCapacity() {
    ShortPocketMap m = new ShortPocketMap(0);
    assertNull(m.put("", (short)505));
    assertTrue(m.containsKey(""));
    assertFalse(m.containsKey("\u001d\r\u0016\u000f\u0004\u001b\u0002"));
  }

  @Test void testInsert() {
    ShortPocketMap m = new ShortPocketMap();
    assertEquals(0, m.size());
    assertNull(m.put("a", (short)505));
    assertEquals(1, m.size());
    assertNull(m.put("b", (short)606));
    assertEquals((short)505, m.get("a"));
    assertEquals((short)606, m.get("b"));
  }

  @Test void testPutAll() {
    ShortPocketMap m = new ShortPocketMap();
    assertEquals(0, m.size());
    assertNull(m.put("a", (short)505));
    assertEquals(1, m.size());
    assertNull(m.put("b", (short)606));
    ShortPocketMap m2 = new ShortPocketMap();
    m2.putAll(m);
    assertEquals(m2.size(), 2);
    assertEquals((short)505, m.get("a"));
    assertEquals((short)606, m.get("b"));
  }

  @Test void testInsertLongKeys() {
    ShortPocketMap m = new ShortPocketMap();
    StringBuilder bldr = new StringBuilder();
    for (int i = 16; i < 65536; i += 16) {
      bldr.append("0011223344556677");
      assertNull(m.put(bldr.toString(), (short)505));
    }
    assertEquals(4095, m.size());
    for (int i = 16; i < 65536; i += 16) {
      assertEquals((short)505, m.get(bldr.substring(0, i)));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {8, 512, 4096})
  void testLotsOfInsertions(int initialCapacity) {
    ShortPocketMap m = new ShortPocketMap(initialCapacity);
    IntFunction<Short> toValue = (v) -> (short) v;
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
    ShortPocketMap m = new ShortPocketMap();
    assertNull(m.put("a", (short)505));
    assertEquals((short)505, m.get("a"));
    assertEquals((short)505, m.put("a", (short)606));
    assertEquals((short)606, m.get("a"));
  }

  @Test void testInsertAndRemoveWithCollisions() {
    ShortPocketMap m = new ShortPocketMap();
    assertNull(m.put("a", (short)505));
    assertEquals((short)505, m.get("a"));

    assertNull(m.put("%($", (short)606));
    assertEquals((short)505, m.get("a"));
    assertEquals((short)606, m.get("%($"));

    assertNull(m.put("?/4-AW\u0000", (short)707));
    assertEquals((short)505, m.get("a"));
    assertEquals((short)606, m.get("%($"));
    assertEquals((short)707, m.get("?/4-AW\u0000"));

    assertEquals((short)505, m.remove("a"));
    assertEquals((short)606, m.get("%($"));
    assertEquals((short)707, m.get("?/4-AW\u0000"));

    assertNull(m.put("a", (short)505));
    assertEquals((short)505, m.get("a"));
  }

  @Test void testReplace() {
    ShortPocketMap m = new ShortPocketMap();
    assertNull(m.replace("a", (short)505));
    assertFalse(m.containsKey("a"));
    m.put("a", (short)505);
    assertEquals((short)505, m.replace("a", (short)606));
    assertEquals((short)606, m.get("a"));
  }

  @Test void testIsEmpty() {
    ShortPocketMap m = new ShortPocketMap();
    assertTrue(m.isEmpty());
    assertNull(m.put("a", (short)505));
    assertFalse(m.isEmpty());
    assertEquals((short)505, m.remove("a"));
    assertTrue(m.isEmpty());
  }

  @Test void testRemove() {
    ShortPocketMap m = new ShortPocketMap();
    assertNull(m.put("a", (short)505));
    assertEquals((short)505, m.remove("a"));
    assertNull(m.remove("a"));
  }

  @Test void testEmptyIterators() {
    ShortPocketMap m = new ShortPocketMap();
    assertFalse(m.keySet().iterator().hasNext());
    assertFalse(m.values().iterator().hasNext());
    assertFalse(m.entrySet().iterator().hasNext());
  }

  @Test void testEntryIterator() {
    ShortPocketMap m = new ShortPocketMap(8);
    List<Short> values = Stream.generate(() -> List.of((short)505, (short)606, (short)707, (short)808)).limit(8).flatMap(List::stream).collect(Collectors.toList());
    for (Short v : values) {
      String k = Integer.toString(m.size());
      assertNull(m.put(k, v));
    }
    assertEquals(32, m.size());

    long observed = 0;
    for (Entry<String, Short> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals(values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;
    }

    assertEquals(0xFFFF_FFFFL, observed);
  }

  @Test void testEntryIteratorMutating() {
    ShortPocketMap m = new ShortPocketMap(8);
    List<Short> values = Stream.generate(() -> List.of((short)505, (short)606, (short)707, (short)808)).limit(8).flatMap(List::stream).collect(Collectors.toList());

    for (Short v : values) {
      String k = Integer.toString(m.size());
      assertNull(m.put(k, v));
    }
    assertEquals(32, m.size());

    for (Iterator<Entry<String, Short>> it = m.entrySet().iterator(); it.hasNext(); ) {
      Entry<String, Short> e = it.next();
      int k = Integer.valueOf(e.getKey());
      assertEquals(values.get(k), e.getValue());
      if (k % 2 == 0) {
        it.remove();
      }
    }
    assertEquals(16, m.size());

    long observed = 0;
    for (Entry<String, Short> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals(values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;

      e.setValue((short) -e.getValue());
    }

    assertEquals(0xAAAA_AAAAL, observed);

    observed = 0;
    for (Entry<String, Short> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals((short) -values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;
    }

    assertEquals(0xAAAA_AAAAL, observed);
  }

  @Test void testReplaceAll() {
    ShortPocketMap m = new ShortPocketMap(8);
    List<Short> values = Stream.generate(() -> List.of((short)505, (short)606, (short)707, (short)808)).limit(8).flatMap(List::stream).collect(Collectors.toList());

    for (Short v : values) {
      String k = Integer.toString(m.size());
      assertNull(m.put(k, v));
    }
    assertEquals(32, m.size());

    m.replaceAll((_k, v) -> (short) -v);
    long observed = 0;
    for (Entry<String, Short> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals((short) -values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;
    }

    assertEquals(0xFFFF_FFFFL, observed);
  }

  @Test void testRemoveEntry() {
    ShortPocketMap m = new ShortPocketMap();
    m.put("a", (short)505);
    assertFalse(m.remove("a", (short)606));
    assertTrue(m.remove("a", (short)505));
    assertNull(m.get("a"));
  }

  @Test void testReplaceEntry() {
    ShortPocketMap m = new ShortPocketMap();
    m.put("a", (short)505);
    assertFalse(m.replace("a", (short)606, (short)808));
    assertTrue(m.replace("a", (short)505, (short)808));
    assertEquals((short)808, m.get("a"));
  }

  @Test void testEquals() {
    ShortPocketMap m = new ShortPocketMap();
    ShortPocketMap m2 = new ShortPocketMap();
    assertFalse(m.equals(null));
    m.put("a", (short)505);
    m.put("bb", (short)606);
    m.put("ccc", (short)707);
    m2.put("a", (short)505);
    m2.put("bb", (short)606);
    assertFalse(m.equals(m2));
    m2.put("ccc", (short)707);
    assertTrue(m.equals(m2));
  }

  @Test void testComputeIfs() {
    ShortPocketMap m = new ShortPocketMap(8);
    m.putAll(Map.of("a", (short)505, "bb", (short)606, "ccc", (short)707, "dddd", (short)808));

    // update
    Short computed = m.computeIfPresent("a", (k, v) -> {
      assertEquals("a", k);
      assertEquals((short)505, v);
      return (short) -(short)505;
    });
    assertEquals((short) -(short)505, computed);
    assertEquals(computed, m.get("a"));
    assertEquals(4, m.size());

    // removal
    computed = m.computeIfPresent("bb", (k, v) -> {
      assertEquals("bb", k);
      assertEquals((short)606, v);
      return null;
    });
    assertNull(computed);
    assertNull(m.get("bb"));
    assertFalse(m.containsKey("bb"));
    assertEquals(3, m.size());

    // removal, not performed
    computed = m.computeIfAbsent("ccc", (k) -> {
      fail("unreachable");
      return (short)808;
    });
    assertNull(computed);
    assertEquals((short)707, m.get("ccc"));
    assertEquals(3, m.size());

    // insert, not performed
    computed = m.computeIfPresent("eeeee", (_k, _v) -> {
      fail("unreachable");
      return (short)808;
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
      return (short)606;
    });
    assertEquals((short)606, computed);
    assertEquals((short)606, m.get("eeeee"));
    assertEquals(4, m.size());
  }

  @Test void testCompute() {
    ShortPocketMap m = new ShortPocketMap(8);
    m.putAll(Map.of("a", (short)505, "bb", (short)606, "ccc", (short)707, "dddd", (short)808));

    // update
    Short computed = m.compute("a", (k, v) -> {
      assertEquals("a", k);
      assertEquals((short)505, v);
      return (short) -(short)505;
    });
    assertEquals((short) -(short)505, computed);
    assertEquals(computed, m.get("a"));
    assertEquals(4, m.size());

    // removal
    computed = m.compute("bb", (k, v) -> {
      assertEquals("bb", k);
      assertEquals((short)606, v);
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
      return (short)606;
    });
    assertEquals((short)606, computed);
    assertEquals((short)606, m.get("eeeee"));
    assertEquals(4, m.size());
  }

  @Test void testMerge() {
    ShortPocketMap m = new ShortPocketMap(8);
    m.putAll(Map.of("a", (short)505, "bb", (short)606, "ccc", (short)707));

    BiFunction<Short, Short, Short> remappingFunction = (v1, v2) -> {
      assertEquals((short)808, v2);
      return (short) -v1;
    };

    assertEquals((short) -(short)505, m.merge("a", (short)808, remappingFunction));
    assertEquals((short) -(short)606, m.merge("bb", (short)808, remappingFunction));
    assertEquals((short) -(short)707, m.merge("ccc", (short)808, remappingFunction));
    assertEquals((short)808, m.merge("dddd", (short)808, (_v1, _v2) -> {
      fail("unreachable");
      return (short)505;
    }));

    assertEquals((short) -(short)505, m.get("a"));
    assertEquals((short) -(short)606, m.get("bb"));
    assertEquals((short) -(short)707, m.get("ccc"));
    assertEquals((short)808, m.get("dddd"));

    assertEquals(4, m.size());
    assertNull(m.merge("a", (short)808, (_v1, _v2) -> null));
    assertEquals(3, m.size());
    assertNull(m.merge("bb", (short)808, (_v1, _v2) -> null));
    assertEquals(2, m.size());
    assertNull(m.merge("ccc", (short)808, (_v1, _v2) -> null));
    assertEquals(1, m.size());
    assertNull(m.merge("dddd", (short)808, (_v1, _v2) -> null));
    assertTrue(m.isEmpty());
  }
}
