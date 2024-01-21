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

class BytePocketMapTest {
  @Test void testCreateNegativeCapacity() {
    assertThrows(IllegalArgumentException.class, () -> new BytePocketMap(-1));
  }

  // should still be able to insert
  @Test void testCreateZeroCapacity() {
    BytePocketMap m = new BytePocketMap(0);
    assertNull(m.put("", (byte)55));
    assertTrue(m.containsKey(""));
    assertFalse(m.containsKey("\u001d\r\u0016\u000f\u0004\u001b\u0002"));
  }

  @Test void testInsert() {
    BytePocketMap m = new BytePocketMap();
    assertEquals(0, m.size());
    assertNull(m.put("a", (byte)55));
    assertEquals(1, m.size());
    assertNull(m.put("b", (byte)66));
    assertEquals((byte)55, m.get("a"));
    assertEquals((byte)66, m.get("b"));
  }

  @Test void testPutAll() {
    BytePocketMap m = new BytePocketMap();
    assertEquals(0, m.size());
    assertNull(m.put("a", (byte)55));
    assertEquals(1, m.size());
    assertNull(m.put("b", (byte)66));
    BytePocketMap m2 = new BytePocketMap();
    m2.putAll(m);
    assertEquals(m2.size(), 2);
    assertEquals((byte)55, m.get("a"));
    assertEquals((byte)66, m.get("b"));
  }

  @Test void testInsertLongKeys() {
    BytePocketMap m = new BytePocketMap();
    StringBuilder bldr = new StringBuilder();
    for (int i = 16; i < 65536; i += 16) {
      bldr.append("0011223344556677");
      assertNull(m.put(bldr.toString(), (byte)55));
    }
    assertEquals(4095, m.size());
    for (int i = 16; i < 65536; i += 16) {
      assertEquals((byte)55, m.get(bldr.substring(0, i)));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {8, 512, 4096})
  void testLotsOfInsertions(int initialCapacity) {
    BytePocketMap m = new BytePocketMap(initialCapacity);
    IntFunction<Byte> toValue = (v) -> (byte) v;
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
    BytePocketMap m = new BytePocketMap();
    assertNull(m.put("a", (byte)55));
    assertEquals((byte)55, m.get("a"));
    assertEquals((byte)55, m.put("a", (byte)66));
    assertEquals((byte)66, m.get("a"));
  }

  @Test void testInsertAndRemoveWithCollisions() {
    BytePocketMap m = new BytePocketMap();
    assertNull(m.put("a", (byte)55));
    assertEquals((byte)55, m.get("a"));

    assertNull(m.put("%($", (byte)66));
    assertEquals((byte)55, m.get("a"));
    assertEquals((byte)66, m.get("%($"));

    assertNull(m.put("?/4-AW\u0000", (byte)77));
    assertEquals((byte)55, m.get("a"));
    assertEquals((byte)66, m.get("%($"));
    assertEquals((byte)77, m.get("?/4-AW\u0000"));

    assertEquals((byte)55, m.remove("a"));
    assertEquals((byte)66, m.get("%($"));
    assertEquals((byte)77, m.get("?/4-AW\u0000"));

    assertNull(m.put("a", (byte)55));
    assertEquals((byte)55, m.get("a"));
  }

  @Test void testReplace() {
    BytePocketMap m = new BytePocketMap();
    assertNull(m.replace("a", (byte)55));
    assertFalse(m.containsKey("a"));
    m.put("a", (byte)55);
    assertEquals((byte)55, m.replace("a", (byte)66));
    assertEquals((byte)66, m.get("a"));
  }

  @Test void testIsEmpty() {
    BytePocketMap m = new BytePocketMap();
    assertTrue(m.isEmpty());
    assertNull(m.put("a", (byte)55));
    assertFalse(m.isEmpty());
    assertEquals((byte)55, m.remove("a"));
    assertTrue(m.isEmpty());
  }

  @Test void testRemove() {
    BytePocketMap m = new BytePocketMap();
    assertNull(m.put("a", (byte)55));
    assertEquals((byte)55, m.remove("a"));
    assertNull(m.remove("a"));
  }

  @Test void testEmptyIterators() {
    BytePocketMap m = new BytePocketMap();
    assertFalse(m.keySet().iterator().hasNext());
    assertFalse(m.values().iterator().hasNext());
    assertFalse(m.entrySet().iterator().hasNext());
  }

  @Test void testEntryIterator() {
    BytePocketMap m = new BytePocketMap(8);
    List<Byte> values = Stream.generate(() -> List.of((byte)55, (byte)66, (byte)77, (byte)88)).limit(8).flatMap(List::stream).collect(Collectors.toList());
    for (Byte v : values) {
      String k = Integer.toString(m.size());
      assertNull(m.put(k, v));
    }
    assertEquals(32, m.size());

    long observed = 0;
    for (Entry<String, Byte> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals(values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;
    }

    assertEquals(0xFFFF_FFFFL, observed);
  }

  @Test void testEntryIteratorMutating() {
    BytePocketMap m = new BytePocketMap(8);
    List<Byte> values = Stream.generate(() -> List.of((byte)55, (byte)66, (byte)77, (byte)88)).limit(8).flatMap(List::stream).collect(Collectors.toList());

    for (Byte v : values) {
      String k = Integer.toString(m.size());
      assertNull(m.put(k, v));
    }
    assertEquals(32, m.size());

    for (Iterator<Entry<String, Byte>> it = m.entrySet().iterator(); it.hasNext(); ) {
      Entry<String, Byte> e = it.next();
      int k = Integer.valueOf(e.getKey());
      assertEquals(values.get(k), e.getValue());
      if (k % 2 == 0) {
        it.remove();
      }
    }
    assertEquals(16, m.size());

    long observed = 0;
    for (Entry<String, Byte> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals(values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;

      e.setValue((byte) -e.getValue());
    }

    assertEquals(0xAAAA_AAAAL, observed);

    observed = 0;
    for (Entry<String, Byte> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals((byte) -values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;
    }

    assertEquals(0xAAAA_AAAAL, observed);
  }

  @Test void testReplaceAll() {
    BytePocketMap m = new BytePocketMap(8);
    List<Byte> values = Stream.generate(() -> List.of((byte)55, (byte)66, (byte)77, (byte)88)).limit(8).flatMap(List::stream).collect(Collectors.toList());

    for (Byte v : values) {
      String k = Integer.toString(m.size());
      assertNull(m.put(k, v));
    }
    assertEquals(32, m.size());

    m.replaceAll((_k, v) -> (byte) -v);
    long observed = 0;
    for (Entry<String, Byte> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals((byte) -values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;
    }

    assertEquals(0xFFFF_FFFFL, observed);
  }

  @Test void testRemoveEntry() {
    BytePocketMap m = new BytePocketMap();
    m.put("a", (byte)55);
    assertFalse(m.remove("a", (byte)66));
    assertTrue(m.remove("a", (byte)55));
    assertNull(m.get("a"));
  }

  @Test void testReplaceEntry() {
    BytePocketMap m = new BytePocketMap();
    m.put("a", (byte)55);
    assertFalse(m.replace("a", (byte)66, (byte)88));
    assertTrue(m.replace("a", (byte)55, (byte)88));
    assertEquals((byte)88, m.get("a"));
  }

  @Test void testEquals() {
    BytePocketMap m = new BytePocketMap();
    BytePocketMap m2 = new BytePocketMap();
    assertFalse(m.equals(null));
    m.put("a", (byte)55);
    m.put("bb", (byte)66);
    m.put("ccc", (byte)77);
    m2.put("a", (byte)55);
    m2.put("bb", (byte)66);
    assertFalse(m.equals(m2));
    m2.put("ccc", (byte)77);
    assertTrue(m.equals(m2));
  }

  @Test void testComputeIfs() {
    BytePocketMap m = new BytePocketMap(8);
    m.putAll(Map.of("a", (byte)55, "bb", (byte)66, "ccc", (byte)77, "dddd", (byte)88));

    // update
    Byte computed = m.computeIfPresent("a", (k, v) -> {
      assertEquals("a", k);
      assertEquals((byte)55, v);
      return (byte) -(byte)55;
    });
    assertEquals((byte) -(byte)55, computed);
    assertEquals(computed, m.get("a"));
    assertEquals(4, m.size());

    // removal
    computed = m.computeIfPresent("bb", (k, v) -> {
      assertEquals("bb", k);
      assertEquals((byte)66, v);
      return null;
    });
    assertNull(computed);
    assertNull(m.get("bb"));
    assertFalse(m.containsKey("bb"));
    assertEquals(3, m.size());

    // removal, not performed
    computed = m.computeIfAbsent("ccc", (k) -> {
      fail("unreachable");
      return (byte)88;
    });
    assertNull(computed);
    assertEquals((byte)77, m.get("ccc"));
    assertEquals(3, m.size());

    // insert, not performed
    computed = m.computeIfPresent("eeeee", (_k, _v) -> {
      fail("unreachable");
      return (byte)88;
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
      return (byte)66;
    });
    assertEquals((byte)66, computed);
    assertEquals((byte)66, m.get("eeeee"));
    assertEquals(4, m.size());
  }

  @Test void testCompute() {
    BytePocketMap m = new BytePocketMap(8);
    m.putAll(Map.of("a", (byte)55, "bb", (byte)66, "ccc", (byte)77, "dddd", (byte)88));

    // update
    Byte computed = m.compute("a", (k, v) -> {
      assertEquals("a", k);
      assertEquals((byte)55, v);
      return (byte) -(byte)55;
    });
    assertEquals((byte) -(byte)55, computed);
    assertEquals(computed, m.get("a"));
    assertEquals(4, m.size());

    // removal
    computed = m.compute("bb", (k, v) -> {
      assertEquals("bb", k);
      assertEquals((byte)66, v);
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
      return (byte)66;
    });
    assertEquals((byte)66, computed);
    assertEquals((byte)66, m.get("eeeee"));
    assertEquals(4, m.size());
  }

  @Test void testMerge() {
    BytePocketMap m = new BytePocketMap(8);
    m.putAll(Map.of("a", (byte)55, "bb", (byte)66, "ccc", (byte)77));

    BiFunction<Byte, Byte, Byte> remappingFunction = (v1, v2) -> {
      assertEquals((byte)88, v2);
      return (byte) -v1;
    };

    assertEquals((byte) -(byte)55, m.merge("a", (byte)88, remappingFunction));
    assertEquals((byte) -(byte)66, m.merge("bb", (byte)88, remappingFunction));
    assertEquals((byte) -(byte)77, m.merge("ccc", (byte)88, remappingFunction));
    assertEquals((byte)88, m.merge("dddd", (byte)88, (_v1, _v2) -> {
      fail("unreachable");
      return (byte)55;
    }));

    assertEquals((byte) -(byte)55, m.get("a"));
    assertEquals((byte) -(byte)66, m.get("bb"));
    assertEquals((byte) -(byte)77, m.get("ccc"));
    assertEquals((byte)88, m.get("dddd"));

    assertEquals(4, m.size());
    assertNull(m.merge("a", (byte)88, (_v1, _v2) -> null));
    assertEquals(3, m.size());
    assertNull(m.merge("bb", (byte)88, (_v1, _v2) -> null));
    assertEquals(2, m.size());
    assertNull(m.merge("ccc", (byte)88, (_v1, _v2) -> null));
    assertEquals(1, m.size());
    assertNull(m.merge("dddd", (byte)88, (_v1, _v2) -> null));
    assertTrue(m.isEmpty());
  }
}
