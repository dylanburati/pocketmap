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

/* template! \(.test_imports//"// no extra imports") */
// no extra imports

/* template_all! [505, 606, 707, 808] */
/* template! class \(.val.disp)PocketMapTest { */
class IntPocketMapTest {
  @Test void testCreateNegativeCapacity() {
    /* template! assertThrows(IllegalArgumentException.class, () -> new \(.val.disp)PocketMap\(.val.generic//"")(-1)); */
    assertThrows(IllegalArgumentException.class, () -> new IntPocketMap(-1));
  }

  // should still be able to insert
  @Test void testCreateZeroCapacity() {
    /* template! \(.val.disp)PocketMap\(.val.generic//"") m = new \(.val.disp)PocketMap\(.val.generic_infer//"")(0); */
    IntPocketMap m = new IntPocketMap(0);
    assertNull(m.put("", 505));
    assertTrue(m.containsKey(""));
    assertFalse(m.containsKey("\u001d\r\u0016\u000f\u0004\u001b\u0002"));
  }

  @Test void testInsert() {
    /* template! \(.val.disp)PocketMap\(.val.generic//"") m = new \(.val.disp)PocketMap\(.val.generic_infer//"")(); */
    IntPocketMap m = new IntPocketMap();
    assertEquals(0, m.size());
    assertNull(m.put("a", 505));
    assertEquals(1, m.size());
    assertNull(m.put("b", 606));
    assertEquals(505, m.get("a"));
    assertEquals(606, m.get("b"));
  }

  @Test void testPutAll() {
    /* template! \(.val.disp)PocketMap\(.val.generic//"") m = new \(.val.disp)PocketMap\(.val.generic_infer//"")(); */
    IntPocketMap m = new IntPocketMap();
    assertEquals(0, m.size());
    assertNull(m.put("a", 505));
    assertEquals(1, m.size());
    assertNull(m.put("b", 606));
    /* template! \(.val.disp)PocketMap\(.val.generic//"") m2 = new \(.val.disp)PocketMap\(.val.generic_infer//"")(); */
    IntPocketMap m2 = new IntPocketMap();
    m2.putAll(m);
    assertEquals(m2.size(), 2);
    assertEquals(505, m.get("a"));
    assertEquals(606, m.get("b"));
  }

  @Test void testInsertLongKeys() {
    /* template! \(.val.disp)PocketMap\(.val.generic//"") m = new \(.val.disp)PocketMap\(.val.generic_infer//"")(); */
    IntPocketMap m = new IntPocketMap();
    StringBuilder bldr = new StringBuilder();
    for (int i = 16; i < 65536; i += 16) {
      bldr.append("0011223344556677");
      assertNull(m.put(bldr.toString(), 505));
    }
    assertEquals(4095, m.size());
    for (int i = 16; i < 65536; i += 16) {
      assertEquals(505, m.get(bldr.substring(0, i)));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {8, 512, 4096})
  void testLotsOfInsertions(int initialCapacity) {
    /* template! \(.val.disp)PocketMap\(.val.generic//"") m = new \(.val.disp)PocketMap\(.val.generic_infer//"")(initialCapacity); */
    IntPocketMap m = new IntPocketMap(initialCapacity);
    /* template! IntFunction<\(.val.view)> toValue = \(.intLambda); */
    IntFunction<Integer> toValue = (v) -> v;
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
    /* template! \(.val.disp)PocketMap\(.val.generic//"") m = new \(.val.disp)PocketMap\(.val.generic_infer//"")(); */
    IntPocketMap m = new IntPocketMap();
    assertNull(m.put("a", 505));
    assertEquals(505, m.get("a"));
    assertEquals(505, m.put("a", 606));
    assertEquals(606, m.get("a"));
  }

  @Test void testInsertAndRemoveWithCollisions() {
    /* template! \(.val.disp)PocketMap\(.val.generic//"") m = new \(.val.disp)PocketMap\(.val.generic_infer//"")(); */
    IntPocketMap m = new IntPocketMap();
    assertNull(m.put("a", 505));
    assertEquals(505, m.get("a"));

    assertNull(m.put("%($", 606));
    assertEquals(505, m.get("a"));
    assertEquals(606, m.get("%($"));

    assertNull(m.put("?/4-AW\u0000", 707));
    assertEquals(505, m.get("a"));
    assertEquals(606, m.get("%($"));
    assertEquals(707, m.get("?/4-AW\u0000"));

    assertEquals(505, m.remove("a"));
    assertEquals(606, m.get("%($"));
    assertEquals(707, m.get("?/4-AW\u0000"));

    assertNull(m.put("a", 505));
    assertEquals(505, m.get("a"));
  }

  @Test void testReplace() {
    /* template! \(.val.disp)PocketMap\(.val.generic//"") m = new \(.val.disp)PocketMap\(.val.generic_infer//"")(); */
    IntPocketMap m = new IntPocketMap();
    assertNull(m.replace("a", 505));
    assertFalse(m.containsKey("a"));
    m.put("a", 505);
    assertEquals(505, m.replace("a", 606));
    assertEquals(606, m.get("a"));
  }

  @Test void testIsEmpty() {
    /* template! \(.val.disp)PocketMap\(.val.generic//"") m = new \(.val.disp)PocketMap\(.val.generic_infer//"")(); */
    IntPocketMap m = new IntPocketMap();
    assertTrue(m.isEmpty());
    assertNull(m.put("a", 505));
    assertFalse(m.isEmpty());
    assertEquals(505, m.remove("a"));
    assertTrue(m.isEmpty());
  }

  @Test void testRemove() {
    /* template! \(.val.disp)PocketMap\(.val.generic//"") m = new \(.val.disp)PocketMap\(.val.generic_infer//"")(); */
    IntPocketMap m = new IntPocketMap();
    assertNull(m.put("a", 505));
    assertEquals(505, m.remove("a"));
    assertNull(m.remove("a"));
  }

  @Test void testEmptyIterators() {
    /* template! \(.val.disp)PocketMap\(.val.generic//"") m = new \(.val.disp)PocketMap\(.val.generic_infer//"")(); */
    IntPocketMap m = new IntPocketMap();
    assertFalse(m.keySet().iterator().hasNext());
    assertFalse(m.values().iterator().hasNext());
    assertFalse(m.entrySet().iterator().hasNext());
  }

  @Test void testEntryIterator() {
    /* template! \(.val.disp)PocketMap\(.val.generic//"") m = new \(.val.disp)PocketMap\(.val.generic_infer//"")(8); */
    IntPocketMap m = new IntPocketMap(8);
    /* template! List<\(.val.view)> values = Stream.generate(() -> List.of(505, 606, 707, 808)).limit(8).flatMap(List::stream).collect(Collectors.toList()); */
    List<Integer> values = Stream.generate(() -> List.of(505, 606, 707, 808)).limit(8).flatMap(List::stream).collect(Collectors.toList());
    /* template! for (\(.val.view) v : values) { */
    for (Integer v : values) {
      String k = Integer.toString(m.size());
      assertNull(m.put(k, v));
    }
    assertEquals(32, m.size());

    long observed = 0;
    /* template! for (Entry<String, \(.val.view)> e : m.entrySet()) { */
    for (Entry<String, Integer> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals(values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;
    }

    assertEquals(0xFFFF_FFFFL, observed);
  }

  @Test void testEntryIteratorMutating() {
    /* template! \(.val.disp)PocketMap\(.val.generic//"") m = new \(.val.disp)PocketMap\(.val.generic_infer//"")(8); */
    IntPocketMap m = new IntPocketMap(8);
    /* template! List<\(.val.view)> values = Stream.generate(() -> List.of(505, 606, 707, 808)).limit(8).flatMap(List::stream).collect(Collectors.toList()); */
    List<Integer> values = Stream.generate(() -> List.of(505, 606, 707, 808)).limit(8).flatMap(List::stream).collect(Collectors.toList());

    /* template! for (\(.val.view) v : values) { */
    for (Integer v : values) {
      String k = Integer.toString(m.size());
      assertNull(m.put(k, v));
    }
    assertEquals(32, m.size());

    /* template! for (Iterator<Entry<String, \(.val.view)>> it = m.entrySet().iterator(); it.hasNext(); ) { */
    for (Iterator<Entry<String, Integer>> it = m.entrySet().iterator(); it.hasNext(); ) {
      /* template! Entry<String, \(.val.view)> e = it.next(); */
      Entry<String, Integer> e = it.next();
      int k = Integer.valueOf(e.getKey());
      assertEquals(values.get(k), e.getValue());
      if (k % 2 == 0) {
        it.remove();
      }
    }
    assertEquals(16, m.size());

    long observed = 0;
    /* template! for (Entry<String, \(.val.view)> e : m.entrySet()) { */
    for (Entry<String, Integer> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      assertEquals(values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;

      /* template! e.setValue(\(.unary_pre//"-")e.getValue()\(.unary_post//"")); */
      e.setValue(-e.getValue());
    }

    assertEquals(0xAAAA_AAAAL, observed);

    observed = 0;
    /* template! for (Entry<String, \(.val.view)> e : m.entrySet()) { */
    for (Entry<String, Integer> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      /* template! assertEquals(\(.unary_pre//"-")values.get(k)\(.unary_post//""), e.getValue()); */
      assertEquals(-values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;
    }

    assertEquals(0xAAAA_AAAAL, observed);
  }

  @Test void testReplaceAll() {
    /* template! \(.val.disp)PocketMap\(.val.generic//"") m = new \(.val.disp)PocketMap\(.val.generic_infer//"")(8); */
    IntPocketMap m = new IntPocketMap(8);
    /* template! List<\(.val.view)> values = Stream.generate(() -> List.of(505, 606, 707, 808)).limit(8).flatMap(List::stream).collect(Collectors.toList()); */
    List<Integer> values = Stream.generate(() -> List.of(505, 606, 707, 808)).limit(8).flatMap(List::stream).collect(Collectors.toList());

    /* template! for (\(.val.view) v : values) { */
    for (Integer v : values) {
      String k = Integer.toString(m.size());
      assertNull(m.put(k, v));
    }
    assertEquals(32, m.size());

    /* template! m.replaceAll((_k, v) -> \(.unary_pre//"-")v\(.unary_post//"")); */
    m.replaceAll((_k, v) -> -v);
    long observed = 0;
    /* template! for (Entry<String, \(.val.view)> e : m.entrySet()) { */
    for (Entry<String, Integer> e : m.entrySet()) {
      int k = Integer.valueOf(e.getKey());
      /* template! assertEquals(\(.unary_pre//"-")values.get(k)\(.unary_post//""), e.getValue()); */
      assertEquals(-values.get(k), e.getValue());
      long mask = 1L << k;
      assertEquals(0L, observed & mask, String.format("unexpected second occurence of %s", e.getKey()));
      observed |= mask;
    }

    assertEquals(0xFFFF_FFFFL, observed);
  }

  @Test void testRemoveEntry() {
    /* template! \(.val.disp)PocketMap\(.val.generic//"") m = new \(.val.disp)PocketMap\(.val.generic_infer//"")(); */
    IntPocketMap m = new IntPocketMap();
    m.put("a", 505);
    assertFalse(m.remove("a", 606));
    assertTrue(m.remove("a", 505));
    assertNull(m.get("a"));
  }

  @Test void testReplaceEntry() {
    /* template! \(.val.disp)PocketMap\(.val.generic//"") m = new \(.val.disp)PocketMap\(.val.generic_infer//"")(); */
    IntPocketMap m = new IntPocketMap();
    m.put("a", 505);
    assertFalse(m.replace("a", 606, 808));
    assertTrue(m.replace("a", 505, 808));
    assertEquals(808, m.get("a"));
  }

  @Test void testEquals() {
    /* template! \(.val.disp)PocketMap\(.val.generic//"") m = new \(.val.disp)PocketMap\(.val.generic_infer//"")(); */
    IntPocketMap m = new IntPocketMap();
    /* template! \(.val.disp)PocketMap\(.val.generic//"") m2 = new \(.val.disp)PocketMap\(.val.generic_infer//"")(); */
    IntPocketMap m2 = new IntPocketMap();
    assertFalse(m.equals(null));
    m.put("a", 505);
    m.put("bb", 606);
    m.put("ccc", 707);
    m2.put("a", 505);
    m2.put("bb", 606);
    assertFalse(m.equals(m2));
    m2.put("ccc", 707);
    assertTrue(m.equals(m2));
  }

  @Test void testComputeIfs() {
    /* template! \(.val.disp)PocketMap\(.val.generic//"") m = new \(.val.disp)PocketMap\(.val.generic_infer//"")(8); */
    IntPocketMap m = new IntPocketMap(8);
    m.putAll(Map.of("a", 505, "bb", 606, "ccc", 707, "dddd", 808));

    // update
    /* template! \(.val.view) computed = m.computeIfPresent(\"a\", (k, v) -> { */
    Integer computed = m.computeIfPresent("a", (k, v) -> {
      assertEquals("a", k);
      assertEquals(505, v);
      /* template! return \(.unary_pre//"-")505\(.unary_post//""); */
      return -505;
    });
    /* template! assertEquals(\(.unary_pre//"-")505\(.unary_post//""), computed); */
    assertEquals(-505, computed);
    assertEquals(computed, m.get("a"));
    assertEquals(4, m.size());

    // removal
    computed = m.computeIfPresent("bb", (k, v) -> {
      assertEquals("bb", k);
      assertEquals(606, v);
      return null;
    });
    assertNull(computed);
    assertNull(m.get("bb"));
    assertFalse(m.containsKey("bb"));
    assertEquals(3, m.size());

    // removal, not performed
    computed = m.computeIfAbsent("ccc", (k) -> {
      fail("unreachable");
      return 808;
    });
    assertNull(computed);
    assertEquals(707, m.get("ccc"));
    assertEquals(3, m.size());

    // insert, not performed
    computed = m.computeIfPresent("eeeee", (_k, _v) -> {
      fail("unreachable");
      return 808;
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
      return 606;
    });
    assertEquals(606, computed);
    assertEquals(606, m.get("eeeee"));
    assertEquals(4, m.size());
  }

  @Test void testCompute() {
    /* template! \(.val.disp)PocketMap\(.val.generic//"") m = new \(.val.disp)PocketMap\(.val.generic_infer//"")(8); */
    IntPocketMap m = new IntPocketMap(8);
    m.putAll(Map.of("a", 505, "bb", 606, "ccc", 707, "dddd", 808));

    // update
    /* template! \(.val.view) computed = m.compute(\"a\", (k, v) -> { */
    Integer computed = m.compute("a", (k, v) -> {
      assertEquals("a", k);
      assertEquals(505, v);
      /* template! return \(.unary_pre//"-")505\(.unary_post//""); */
      return -505;
    });
    /* template! assertEquals(\(.unary_pre//"-")505\(.unary_post//""), computed); */
    assertEquals(-505, computed);
    assertEquals(computed, m.get("a"));
    assertEquals(4, m.size());

    // removal
    computed = m.compute("bb", (k, v) -> {
      assertEquals("bb", k);
      assertEquals(606, v);
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
      return 606;
    });
    assertEquals(606, computed);
    assertEquals(606, m.get("eeeee"));
    assertEquals(4, m.size());
  }

  @Test void testMerge() {
    /* template! \(.val.disp)PocketMap\(.val.generic//"") m = new \(.val.disp)PocketMap\(.val.generic_infer//"")(8); */
    IntPocketMap m = new IntPocketMap(8);
    m.putAll(Map.of("a", 505, "bb", 606, "ccc", 707));

    /* template! BiFunction<\(.val.view), \(.val.view), \(.val.view)> remappingFunction = (v1, v2) -> { */
    BiFunction<Integer, Integer, Integer> remappingFunction = (v1, v2) -> {
      assertEquals(808, v2);
      /* template! return \(.unary_pre//"-")v1\(.unary_post//""); */
      return -v1;
    };
    
    /* template! assertEquals(\(.unary_pre//"-")505\(.unary_post//""), m.merge(\"a\", 808, remappingFunction)); */
    assertEquals(-505, m.merge("a", 808, remappingFunction));
    /* template! assertEquals(\(.unary_pre//"-")606\(.unary_post//""), m.merge(\"bb\", 808, remappingFunction)); */
    assertEquals(-606, m.merge("bb", 808, remappingFunction));
    /* template! assertEquals(\(.unary_pre//"-")707\(.unary_post//""), m.merge(\"ccc\", 808, remappingFunction)); */
    assertEquals(-707, m.merge("ccc", 808, remappingFunction));
    assertEquals(808, m.merge("dddd", 808, (_v1, _v2) -> {
      fail("unreachable");
      return 505;
    }));

    /* template! assertEquals(\(.unary_pre//"-")505\(.unary_post//""), m.get(\"a\")); */
    assertEquals(-505, m.get("a"));
    /* template! assertEquals(\(.unary_pre//"-")606\(.unary_post//""), m.get(\"bb\")); */
    assertEquals(-606, m.get("bb"));
    /* template! assertEquals(\(.unary_pre//"-")707\(.unary_post//""), m.get(\"ccc\")); */
    assertEquals(-707, m.get("ccc"));
    assertEquals(808, m.get("dddd"));

    assertEquals(4, m.size());
    assertNull(m.merge("a", 808, (_v1, _v2) -> null));
    assertEquals(3, m.size());
    assertNull(m.merge("bb", 808, (_v1, _v2) -> null));
    assertEquals(2, m.size());
    assertNull(m.merge("ccc", 808, (_v1, _v2) -> null));
    assertEquals(1, m.size());
    assertNull(m.merge("dddd", 808, (_v1, _v2) -> null));
    assertTrue(m.isEmpty());
  }
}
