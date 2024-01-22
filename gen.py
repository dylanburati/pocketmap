import itertools
import json
import subprocess
import re
import sys

configs = [
    {
        "val": {"t": "boolean", "view": "Boolean", "disp": "Boolean"},
        "example_values": ["false", "true"],
        "intLambda": "(v) -> v % 2 == 0",
        "unary_pre": "!",
    },
    {
        "val": {"t": "byte", "view": "Byte", "disp": "Byte"},
        "example_values": ["(byte)55", "(byte)66", "(byte)77", "(byte)88"],
        "demote": "(byte) ",
        "intLambda": "(v) -> (byte) v",
        "unary_pre": "(byte) -",
    },
    {
        "val": {"t": "short", "view": "Short", "disp": "Short"},
        "example_values": ["(short)505", "(short)606", "(short)707", "(short)808"],
        "demote": "(short) ",
        "intLambda": "(v) -> (short) v",
        "unary_pre": "(short) -",
    },
    {
        "val": {"t": "long", "view": "Long", "disp": "Long"},
        "example_values": ["505L", "606L", "707L", "808L"],
        "intLambda": "(v) -> (long) v",
    },
    {
        "val": {"t": "float", "view": "Float", "disp": "Float"},
        "example_values": ["5.5f", "6.25f", "7.125f", "8.0625f"],
        "intLambda": "(v) -> (float) v",
    },
    {
        "val": {"t": "double", "view": "Double", "disp": "Double"},
        "example_values": ["5.5", "6.25", "7.125", "8.0625"],
        "intLambda": "(v) -> (double) v",
    },
]
src_only_configs = [
    {
        "val": {
            "t": "Object",
            "view": "V",
            "generic": "<V>",
            "generic_infer": "<>",
            "generic_any": "<?>",
            "disp": "",
            "object": True,
        }
    },
]
test_only_configs = [
    {
        "val": {
            "t": "List<Integer>",
            "view": "List<Integer>",
            "generic": "<List<Integer>>",
            "generic_infer": "<>",
            "disp": "",
            "object": True,
        },
        "example_values": ['List.of(505, 10)', 'List.of(606, 12)', 'List.of(707, 14)', 'List.of(808, 16)'],
        "intLambda": "v -> List.of(v)",
        "unary_pre": "reversed(",
        "unary_post": ")",
        "test_imports": "import static dev.dylanburati.pocketmap.Helpers.*;"
    }
]

base_src_path = "pocketmap/src/main/java/dev/dylanburati/pocketmap"
with open(f"{base_src_path}/IntPocketMap.java", "r", encoding="utf-8") as fp:
    src_lines = [line.rstrip() for line in fp.readlines()]

base_test_path = "pocketmap/src/test/java/dev/dylanburati/pocketmap"
with open(
    f"{base_test_path}/IntPocketMapTest.java", "r", encoding="utf-8"
) as fp:
    test_lines = [line.rstrip() for line in fp.readlines()]

template_rgx = re.compile(r"^([ ]*)/\* template(\([0-9]+\))?! (.*) \*/")
template_all_rgx = re.compile(r"^[ ]*/\* template_all! (.*) \*/")


def fill_templates(configs, lines):
    result = [[] for _ in configs]
    i = 0
    config_b = b"".join((json.dumps(c).encode("utf-8") + b"\n") for c in configs)
    unconditional_replaces = [{} for _ in configs]
    while i < len(lines):
        template_all_match = template_all_rgx.match(lines[i])
        if template_all_match:
            srcs = json.loads(template_all_match.group(1))
            for j in range(len(result)):
                if dests := configs[j].get("example_values"):
                    for src, dst in zip(srcs, itertools.cycle(dests)):
                        unconditional_replaces[j][str(src)] = dst
                if configs[j].get("keep"):
                    result[j].append(lines[i])
            i += 1
            continue
        to_add = [[] for _ in configs]
        template_match = template_rgx.match(lines[i])
        if not template_match:
            for j in range(len(result)):
                to_add[j].append(lines[i])
            i += 1
        else:
            replace_count = 1
            indent = template_match.group(1)
            if count_arg := template_match.group(2):
                replace_count = int(count_arg[1:-1])
            jq_script = (
                r'def equals: if .[0] then "\(.[1]).equals(\(.[2]))" else "\(.[1]) == (\(.[3])) \(.[2])" end; '
                r'def castUnsafe: if .[0] then "castUnsafe(\(.[1]))" else .[1] end; '
                f'"{template_match.group(3)}"'
            )
            proc = subprocess.Popen(
                ["jq", "-c", jq_script],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            out_bytes, err_bytes = proc.communicate(config_b + b"\n")
            if err_bytes:
                print(f"template error on line {i+1}", file=sys.stderr)
                sys.stderr.buffer.write(err_bytes)
                break
            for j, render in enumerate(map(json.loads, map(bytes.rstrip, out_bytes.splitlines()))):
                to_add[j].extend(
                    (indent + e) for e in render.split("\n") if e != ""
                )
            for j in range(len(result)):
                if configs[j].get("keep"):
                    result[j].append(lines[i])
            i += 1 + replace_count

        for j in range(len(to_add)):
            for line in to_add[j]:
                replace_indices = []
                for src, dst in unconditional_replaces[j].items():
                    line_idx = 0
                    while line_idx < len(line):
                        nxt = line.find(src, line_idx)
                        if nxt == -1:
                            break
                        replace_indices.append((nxt, nxt + len(src), dst))
                        line_idx = nxt + len(src)
                replace_indices.sort(key=lambda e: -e[0])
                for start, end, dst in replace_indices:
                    line = line[:start] + dst + line[end:]
                result[j].append(line)
    return result


int_config = {
    "val": {"t": "int", "view": "Integer", "disp": "Int"},
    "intLambda": "(v) -> v",
    "keep": True,
}
src_sanity, *src_outs = fill_templates([int_config, *configs, *src_only_configs], src_lines)
if src_sanity != src_lines:
    import pdb

    pdb.set_trace()
    sys.exit(1)
test_sanity, *test_outs = fill_templates([int_config, *configs, *test_only_configs], test_lines)
if test_sanity != test_lines:
    import pdb

    pdb.set_trace()
    sys.exit(1)

for lst, c in zip(src_outs, configs + src_only_configs):
    src_file = f"{c['val']['disp']}PocketMap.java"
    with open(f"{base_src_path}/{src_file}", "w", encoding="utf-8") as fp:
        fp.write("\n".join(lst))
        fp.write("\n")
for lst, c in zip(test_outs, configs + test_only_configs):
    test_file = f"{c['val']['disp']}PocketMapTest.java"
    with open(f"{base_test_path}/{test_file}", "w", encoding="utf-8") as fp:
        fp.write("\n".join(lst))
        fp.write("\n")
