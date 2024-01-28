## pocketmap

This is an alternative hash map implementation for Java. It uses [open addressing][1], while the standard
java.util.HashMap uses [separate chaining][2]. It only supports `byte[]` or `String` keys,
and is meant to store "resting" data with as little memory as possible.
This is accomplished mostly through an indirect key storage mechanism.

### Benchmarks

Graphs: https://observablehq.com/@dylan-burati-ws/pocketmap-benchmarks \
Methodology: https://github.com/dylanburati/tree/main/benchmarks

### Memory usage analysis

```
(typical) keys:   String[capacity]
          values: VALUE_TYPE[capacity]
each key array element is a java OOP (Ordinary Object Pointer)
    -> capacity * 4 (at least size * (32/7) given our load factor of 7/8)
                    (at most size * (64/7) following the first map resize)
each non-null element refers to a java.lang.String
    -> size * 24
each java.lang.String refers to a byte[]
    -> size * 16 + sum(8*ceil(k.byteLength/8) for k in keys)
total >= 44.57*size + (combined byte length of all keys)
         [when key lengths fit perfectly, size = capacity*7/8]
total <  56.14*size + (combined byte length of all keys)
         [when key lengths are 9,17,etc. and size = capacity*7/16]

(indirect) keyStorage: ArrayList<ByteBuffer>
           keys:       long[capacity]
           values:     VALUE_TYPE[capacity]
each key array element encodes the offset and length of a byte-slice in keyStorage
    -> capacity * 8
each byte buffer is a fixed size, large enough to minimize padding losses
    -> BUF_SIZE * ceil((combined byte length of all keys) / BUF_SIZE)
total >= 9.14*size + (combined byte length of all keys)
total < 18.28*size + (combined byte length of all keys) + BUF_SIZE
```

Memory layout numbers checked on [repl.it][3] with OpenJDK 17.0.5, GraalVM CE.

### Caveats

In exchange for the memory savings:

- removing keys doesn't free up any memory, as this would require malloc-style management of keyStorage
  - [redis][4] is a better fit for maps which live much longer than their average entry. Its memory usage
    for the benchmark data is 1.24 GiB, which is lower than IntPocketMap on JDKs 17 and 21 (JDK 11 is
    more agressive in cleaning up temporary strings passed to `Map.put()`).
- hash codes are not cached, so resizing takes a bit longer
- when using the `Map<String, _>` variants, iterating over keys and entries takes longer because each
  call to `next()` or `next().getKey()` constructs a string from the byte slice.

[1]: https://en.wikipedia.org/wiki/Hash_table#Open_addressing
[2]: https://en.wikipedia.org/wiki/Hash_table#Separate_chaining
[3]: https://replit.com/@dylanburati/ClassLayout
[4]: https://github.com/redis/redis
