package dev.dylanburati.pocketmap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import com.github.luben.zstd.ZstdInputStream;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

@State(Scope.Benchmark)
public class StringIntMapBenchmark {
  @Benchmark
  @BenchmarkMode(Mode.SingleShotTime)
  public void wordcountSimulatedIntPocketMap(Blackhole bh) {
    bh.consume(wordcountSimulated(IntPocketMap.newUtf8()));
  }

  @Benchmark
  @BenchmarkMode(Mode.SingleShotTime)
  public void wordcountSimulatedHashMap(Blackhole bh) {
    bh.consume(wordcountSimulated(new HashMap<>()));
  }

  @Benchmark
  @BenchmarkMode(Mode.SingleShotTime)
  public void wordcountSimulatedObject2IntMap(Blackhole bh) {
    bh.consume(wordcountSimulated(new Object2IntOpenHashMap<>()));
  }

  //@Benchmark
  //@BenchmarkMode(Mode.SingleShotTime)
  //public void wordcountEnglishIntPocketMap(Blackhole bh) {
    //bh.consume(wordcountEnglish(IntPocketMap.newUtf8()));
  //}

  //@Benchmark
  //@BenchmarkMode(Mode.SingleShotTime)
  //public void wordcountEnglishHashMap(Blackhole bh) {
    //bh.consume(wordcountEnglish(new HashMap<>()));
  //}

  //@Benchmark
  //@BenchmarkMode(Mode.SingleShotTime)
  //public void wordcountEnglishObject2IntMap(Blackhole bh) {
    //bh.consume(wordcountEnglish(new Object2IntOpenHashMap<>()));
  //}

  private static int genWordId(double uniform) {
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
    return (int) Math.pow(-0.01 * 15.768233989819334 * uniform + 0.9870018865063785, -100.0);
  }

  private static final double[] LENGTH_CDF = new double[]{
    2.55402880e-15, 3.73483535e-07, 2.06251620e-04, 4.60037401e-03,
    2.77018313e-02, 8.59221455e-02, 1.82026193e-01, 3.04121079e-01,
    4.34720260e-01, 5.58784740e-01, 6.67021855e-01, 7.55676596e-01,
    8.24886736e-01, 8.76934270e-01, 9.14931000e-01, 9.42014131e-01,
    9.60943967e-01, 9.73962076e-01, 9.82793792e-01, 9.88716864e-01,
    9.92650419e-01, 9.95240748e-01, 9.96934095e-01, 9.98034022e-01,
    9.98744497e-01, 9.99201152e-01, 9.99493382e-01, 9.99679661e-01,
    9.99797989e-01, 9.99872918e-01, 9.99920231e-01, 9.99950030e-01
  };

  private static int genWordLen(double uniform) {
    // pdf = const * exp(-(x - 8.5)**2 / 2x)
    int i = Arrays.binarySearch(LENGTH_CDF, uniform);
    return i >= 0 ? i : -i - 1;
  }

  public int wordcountSimulated(Map<String, Integer> m) {
    byte[] alph = "pfscxkde".getBytes(StandardCharsets.US_ASCII);
    byte[] wbuf = new byte[32];
    Random r = new Random(0L);
    for (int i = 0; i < 100_000_000; i++) {
      double uniform = r.nextDouble();
      int wlen = genWordLen(uniform);
      for (int wid = genWordId(uniform), j = 0; j < wlen; j++) {
        wbuf[j] = alph[(wid >> (3 * (j%9))) & 7];
      }
      String word = new String(wbuf, 0, wlen, StandardCharsets.US_ASCII);
      m.merge(word, 1, (v1, v2) -> v1 + v2);
    }
    System.out.println("Size: " + m.size());
    return m.size();
  }

  public int wordcountEnglish(Map<String, Integer> m) {
    String last = null;
    String line = null;
    int[] distribution = new int[32];
    try (
        InputStream enc = getClass().getClassLoader().getResourceAsStream("gutenberg.zst");
        BufferedReader r = new BufferedReader(new InputStreamReader(new ZstdInputStream(enc), StandardCharsets.UTF_8));
    ) {
      Pattern reSpace = Pattern.compile("\\s+");
      Pattern reNonWord = Pattern.compile("[^a-zA-Z0-9'&-]");
      while ((line = r.readLine()) != null) {
        String[] words = reSpace.split(reNonWord.matcher(line).replaceAll(" "));
        for (String w : words) {
          if (!w.isEmpty()) {
            if (last != null) {
              distribution[Math.min(31, last.length()+w.length()-2)]++;
              m.merge(last + " " + w, 1, (v1, v2) -> v1 + v2);
            }
            last = w;
          }
        }
      }
      System.out.println("Size: " + m.size());
      System.out.println("Distribution: " + Arrays.toString(distribution));
      return m.size();
    } catch (IOException e) {
      e.printStackTrace();
    }
    return -1;
  }
}
