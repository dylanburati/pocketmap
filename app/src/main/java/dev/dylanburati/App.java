package dev.dylanburati;

import dev.dylanburati.pocketmap.IntPocketMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class App {
  private static int genWordId(Random r) {
    // Prob of returning x is proportional to (x+3.7) ** -1.01
    // Similar distribution to English

    // d/dx cdf = const * (x+3.7) ** -1.01
    // cdf = const * ( [ -100 * (x+3.7) ** -0.01 ] - [ -100 * (3.7) ** -0.01 ] )
    // cdf = 100 * const * [ (3.7) ** -0.01 - (x+3.7) ** -0.01 ]

    // [ (3.7) ** -0.01 - (x+3.7) ** -0.01 ] = 0.01 * const * cdf
    // x + 3.7 = (-0.01 * const * cdf + (3.7) ** -0.01) ** -100

    // set maximum of x to 2**27 ->
    // 2**27 = (-0.01 * const * 1 + (3.7**0.01)) ** -100
    // import scipy.optimize
    // scipy.optimize.minimize(lambda x: (27 + 100 * np.log2(-x/100 + (3.7 ** -0.01))) ** 2, 1).x[0]

    // ignoring the lhs constant 3.7
    return (int) Math.pow(-0.01 * 15.768233989819334 * r.nextDouble() + 0.9870018865063785, -100.0);
  }

  private static int wordcount(Map<String, Integer> m) {
    byte[] alph = "pfscxkde".getBytes(StandardCharsets.US_ASCII);
    byte[] wbuf = new byte[9];
    Random r = new Random(0L);
    for (int i = 0; i < 100_000_000; i++) {
      int wlen = 0;
      for (int wid = genWordId(r); wid > 0 && wlen < wbuf.length; wid = wid >> 3) {
        wbuf[wlen++] = alph[wid & 7];
      }
      String word = new String(wbuf, 0, wlen, StandardCharsets.US_ASCII);
      m.merge(word, 1, (v1, v2) -> v1 + v2);
    }
    System.out.println("Size: " + m.size());
    return m.size();
  }

  public static void main(String[] args) {
    var m = switch (args.length > 0 ? args[0] : "") {
      case "java.util" -> new HashMap<String, Integer>();
      case "fastutil" -> new Object2IntOpenHashMap<String>();
      default -> IntPocketMap.newUtf8();
    };
    wordcount(m);
  }
}
