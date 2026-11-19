"""Dev only: copies pack MM structures into the dev world, swapping blocks of mods the dev run lacks for iron blocks.
MM controllers and ports stay real, so port-tier variants can be tested. Usage: python3 tools/dev_mm_structures.py <pack minecraft dir>"""
import gzip, io, os, struct, sys

KEEP = {"minecraft", "mm", "mekanism", "mekanismgenerators", "voidminers", "ae2"}
NAMES = ["water_condenser_tier_1", "water_condenser_tier_2", "water_condenser_tier_3",
         "auto_sieve_tier_1", "auto_sieve_tier_2", "auto_sieve_tier_3", "auto_sieve_tier_4"]

def rd(f, t):
    u = lambda fmt, n: struct.unpack(">" + fmt, f.read(n))[0]
    if t == 1: return u("b", 1)
    if t == 2: return u("h", 2)
    if t == 3: return u("i", 4)
    if t == 4: return u("q", 8)
    if t == 5: return u("f", 4)
    if t == 6: return u("d", 8)
    if t == 7: return f.read(u("i", 4))
    if t == 8: return f.read(u("H", 2)).decode("utf-8")
    if t == 9:
        et, n = u("b", 1), u("i", 4)
        return (et, [rd(f, et) for _ in range(n)])
    if t == 10:
        out = {}
        while True:
            tt = u("b", 1)
            if tt == 0: return out
            name = f.read(u("H", 2)).decode("utf-8")
            out[name] = (tt, rd(f, tt))
    if t == 11: return [u("i", 4) for _ in range(u("i", 4))]
    if t == 12: return [u("q", 8) for _ in range(u("i", 4))]
    raise ValueError(t)

def wr(f, t, v):
    p = lambda fmt, x: f.write(struct.pack(">" + fmt, x))
    if t == 1: p("b", v)
    elif t == 2: p("h", v)
    elif t == 3: p("i", v)
    elif t == 4: p("q", v)
    elif t == 5: p("f", v)
    elif t == 6: p("d", v)
    elif t == 7: p("i", len(v)); f.write(v)
    elif t == 8: b = v.encode("utf-8"); p("H", len(b)); f.write(b)
    elif t == 9:
        et, items = v; p("b", et); p("i", len(items))
        for i in items: wr(f, et, i)
    elif t == 10:
        for name, (tt, vv) in v.items():
            p("b", tt); b = name.encode("utf-8"); p("H", len(b)); f.write(b); wr(f, tt, vv)
        p("b", 0)
    elif t == 11: p("i", len(v)); [p("i", x) for x in v]
    elif t == 12: p("i", len(v)); [p("q", x) for x in v]

def convert(src, dst):
    f = io.BytesIO(gzip.open(src).read())
    assert f.read(1) == b"\x0a"; f.read(struct.unpack(">H", f.read(2))[0])
    root = rd(f, 10)
    swapped = set()
    for i, e in enumerate(root["palette"][1][1]):
        name = e["Name"][1]
        if name.split(":")[0] not in KEEP:
            e["Name"] = (8, "minecraft:iron_block"); e.pop("Properties", None); swapped.add(i)
    for b in root["blocks"][1][1]:
        if b["state"][1] in swapped: b.pop("nbt", None)
    out = io.BytesIO(); out.write(b"\x0a\x00\x00"); wr(out, 10, root)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with gzip.open(dst, "wb") as g: g.write(out.getvalue())
    return len(swapped)

pack = sys.argv[1]
for n in NAMES:
    src = os.path.join(pack, "kubejs/data/mbtool/mbtool_structures/custom_multiblocks", n + ".nbt")
    dst = os.path.join("run/saves/test/datapacks/pi/data/pi/mbplacer/structures/dev_mm", n + ".nbt")
    print(n, "swapped palette entries:", convert(src, dst))
