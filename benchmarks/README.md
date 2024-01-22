## Benchmarks

The `bigrams` benchmarks use `Map.merge` to count the occurences of consecutive word
pairs in a compressed input file at `./src/main/resources/gutenberg.zst`.
They run for HashMap, IntPocketMap, and TreeMap.

My copy of `gutenberg.zst` comes from these commands:

```console
# as part of https://github.com/pgcorpus/gutenberg get_data.py
$ rsync -amv --include */ --include [p123456789][g0123456789]*[.-][t0][x.]t[x.]*[t8] --exclude * aleph.gutenberg.org::gutenberg data/.mirror/
$ find data/.mirror/1 -type f -print0 | xargs -0 sed -n '/\(Start\|START\).*\(Gutenberg\|GUTENBERG\)/,/\(End\|END\).*\(Gutenberg\|GUTENBERG\)/p' | zstd -o /path/to/resources/gutenberg.zst
/*stdin*\            : 35.73%   (820240893 => 293067861 bytes, /path/to/resources/gutenberg.zst)
```

The size is ~23 million, so about 25% of the `merge` operations are inserts and 75% are updates.

```sh
java -jar benchmarks/target/benchmarks.jar -prof mempool -prof stack -f 1 -i 3 -gc true
```
