## Benchmarks

The `wordcount` benchmarks and `app.jar` use `Map.merge` to count the occurences of
words. To minimize external time spent, the input stream is simulated, but it's designed
to mimic data from the Gutenberg project:

```console
# as part of https://github.com/pgcorpus/gutenberg get_data.py
$ rsync -amv --include */ --include [p123456789][g0123456789]*[.-][t0][x.]t[x.]*[t8] --exclude * aleph.gutenberg.org::gutenberg data/.mirror/
$ find data/.mirror/1 -type f -print0 | xargs -0 sed -n '/\(Start\|START\).*\(Gutenberg\|GUTENBERG\)/,/\(End\|END\).*\(Gutenberg\|GUTENBERG\)/p' | zstd -o /path/to/resources/gutenberg.zst
/*stdin*\            : 35.73%   (820240893 => 293067861 bytes, /path/to/resources/gutenberg.zst)
```

The random stream has approximately the same probability-to-rank distribution
([Zipf's law](https://en.wikipedia.org/wiki/Zipf%27s_law)) as the _bigrams_ in that dataset.
The length distribution for each key is modeled on the bigrams as well. This gives a balance of
~4 read/updates per write, with some keys accessed more than others.

### Running

```console
export JDKV=jdk17  # or 11 or 21
java -version      # make sure this matches JDKV

# JMH
java -jar benchmarks/target/benchmarks.jar -prof mempool -prof stack -f 1 -i 3 -gc true \
  -rff "benchmarks/jmh_${JDKV}_$(git show-ref --hash=8 --head | head -n1).json" -rf json

# async-profiler
# proccorder
for K in 'java.util HashMap' ' IntPocketMap' 'fastutil Object2IntMap'; do
  ARG="${K% *}"
  M="${K#* }"

  java -jar app/target/app.jar "$ARG" & \
  { PID=$!; sleep 0.5;
    asprof -e cpu -i 1ms -t start \
      -f "benchmarks/asprof_${JDKV}_${M}_$(git show-ref --head --hash=8 | head -n1).html" $PID && \
    wait $PID && asprof stop $PID; }
  
  proccorder/target/release/proccorder java -jar app/target/app.jar "$ARG" > \ 
    "benchmarks/_${JDKV}_${M}_$(git show-ref --head --hash=8 | head -n1).json"
done
```
