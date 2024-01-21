import itertools
import json
import subprocess
import re
import sys

configs = [
    {"val": {"t": "boolean", "view": "Boolean", "disp": "Boolean"}, "example_values": ["false", "true"], "intLambda": "(v) -> v % 2 == 0", "unary_pre": "!"},
    {"val": {"t": "byte", "view": "Byte", "disp": "Byte"}, "example_values": ["(byte)55", "(byte)66", "(byte)77", "(byte)88"], "demote": "(byte) ", "intLambda": "(v) -> (byte) v", "unary_pre": "(byte) -"},
    {"val": {"t": "short", "view": "Short", "disp": "Short"}, "example_values": ["(short)505", "(short)606", "(short)707", "(short)808"], "demote": "(short) ", "intLambda": "(v) -> (short) v", "unary_pre": "(short) -"},
    {"val": {"t": "long", "view": "Long", "disp": "Long"}, "example_values": ["505L", "606L", "707L", "808L"], "intLambda": "(v) -> (long) v"},
    {"val": {"t": "float", "view": "Float", "disp": "Float"}, "example_values": ["5.5f", "6.25f", "7.125f", "8.0625f"], "intLambda": "(v) -> (float) v"},
    {"val": {"t": "double", "view": "Double", "disp": "Double"}, "example_values": ["5.5", "6.25", "7.125", "8.0625"], "intLambda": "(v) -> (double) v"},
]

base_src_path = "lib/src/main/java/dev/dylanburati/shrinkwrap"
with open(f"{base_src_path}/CompactStringIntMap.java", "r", encoding="utf-8") as fp:
    src_lines = [line.rstrip() for line in fp.readlines()]

base_test_path = "lib/src/test/java/dev/dylanburati/shrinkwrap"
with open(f"{base_test_path}/CompactStringIntMapTest.java", "r", encoding="utf-8") as fp:
	test_lines = [line.rstrip() for line in fp.readlines()]

template_rgx = re.compile(r"^([ ]*)/\* template(\([0-9]+\))?! (.*) \*/")
template_all_rgx = re.compile(r"^[ ]*/\* template_all! (.*) \*/")
def fill_templates(config, lines, keep=False):
    result = []
    i = 0
    config_b = json.dumps(config).encode("utf-8")
    unconditional_replaces = {}
    while i < len(lines):
        template_all_match = template_all_rgx.match(lines[i])
        if template_all_match:
            srcs = json.loads(template_all_match.group(1))
            if (dests := config.get("example_values")):
                for src, dst in zip(srcs, itertools.cycle(dests)):
                    unconditional_replaces[str(src)] = dst
            if keep:
                result.append(lines[i])
            i += 1
            continue
        to_add = []
        template_match = template_rgx.match(lines[i])
        if not template_match:
            to_add.append(lines[i])
            i += 1
        else:
            replace_count = 1
            indent = template_match.group(1)
            if (count_arg := template_match.group(2)):
                replace_count = int(count_arg[1:-1])
            jq_script = (
                fr'def equals: if .[0] then "\(.[1]).equals(\(.[2]))" else "\(.[1]) == \(.[2])" end; "{template_match.group(3)}"'
            )
            proc = subprocess.Popen(
                ["jq", "-r", jq_script],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            out_bytes, err_bytes = proc.communicate(config_b + b"\n")
            if err_bytes:
                print(f"template error on line {i+1}", file=sys.stderr)
                sys.stderr.buffer.write(err_bytes)
                break
            if keep:
                result.append(lines[i])
            to_add.extend((indent + e) for e in out_bytes.decode("utf-8").split("\n") if e != "")
            i += 1 + replace_count

        for line in to_add:
            replace_indices = []
            for src, dst in unconditional_replaces.items():
                line_idx = 0
                while line_idx < len(dst):
                    nxt = line.find(src, line_idx)
                    if nxt == -1:
                        break
                    replace_indices.append((nxt, nxt+len(src), dst))
                    line_idx = nxt + len(src)
            replace_indices.sort(key=lambda e: -e[0])
            for start, end, dst in replace_indices:
                line = line[:start] + dst + line[end:]
            result.append(line)
    return result


int_config = {"val": {"t": "int", "view": "Integer", "disp": "Int"}, "intLambda": "(v) -> v"}
src_sanity = fill_templates(int_config, src_lines, keep=True)
if src_sanity != src_lines:
    import pdb
    pdb.set_trace()
    sys.exit(1)
test_sanity = fill_templates(int_config, test_lines, keep=True)
if test_sanity != test_lines:
    import pdb
    pdb.set_trace()
    sys.exit(1)

for c in configs:
    with open(f"{base_src_path}/CompactString{c['val']['disp']}Map.java", "w", encoding="utf-8") as fp:
        fp.write("\n".join(fill_templates(c, src_lines)))
        fp.write("\n")
for c in configs:
    with open(f"{base_test_path}/CompactString{c['val']['disp']}MapTest.java", "w", encoding="utf-8") as fp:
        fp.write("\n".join(fill_templates(c, test_lines)))
        fp.write("\n")
