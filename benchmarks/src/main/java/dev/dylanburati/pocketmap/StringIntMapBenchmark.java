package dev.dylanburati.pocketmap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import com.github.luben.zstd.ZstdInputStream;

import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
public class StringIntMapBenchmark {
  @Benchmark
  @BenchmarkMode(Mode.SingleShotTime)
  public void bigramsPocketMap(Blackhole bh) {
    bh.consume(bigrams(new IntPocketMap()));
  }

  @Benchmark
  @BenchmarkMode(Mode.SingleShotTime)
  public void bigramsHashMap(Blackhole bh) {
    bh.consume(bigrams(new HashMap<>()));
  }

  @Benchmark
  @BenchmarkMode(Mode.SingleShotTime)
  public void bigramsTreeMap(Blackhole bh) {
    bh.consume(bigrams(new TreeMap<>()));
  }

  //@Benchmark
  //@BenchmarkMode(Mode.SingleShotTime)
  //public void bigramsMapDBMap(Blackhole bh) {
    //DB db = DBMaker.memoryDB().make();
    //bh.consume(bigrams(db.hashMap("map", Serializer.STRING, Serializer.INTEGER).createOrOpen()));
    //db.close();
  //}

  public int bigrams(Map<String, Integer> m) {
    String last = null;
    String line = null;
    try (
        InputStream is = getClass().getClassLoader().getResourceAsStream("gutenberg.zst");
        BufferedReader r = new BufferedReader(new InputStreamReader(new ZstdInputStream(is), StandardCharsets.UTF_8));
    ) {
      while ((line = r.readLine()) != null) {
        String[] words = line.replaceAll("[^a-zA-Z0-9'&-]", " ").split("\\s+");
        for (String w : words) {
          if (!w.isEmpty()) {
            if (last != null) {
              m.merge(last + " " + w, 1, (v1, v2) -> v1 + v2);
            }
            last = w;
          }  
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return m.size();
  }
}
