#!/usr/bin/env python3
"""Which platform (android.jar) methods real apps call: the modern API corpus for the privacy API model.

The classifier's labels come from SuSi (Android 4.2) and FlowDroid; this corpus says which public
methods of today's SDK apps actually reach, and how many apps reach each, so labelling effort goes
where the model is used and its accuracy can be measured on what apps call (see evaluate_api_model.py).

For every APK: the method references of its DEX files whose class is not defined in the APK, plus calls
made through an app class (`MainActivity.getSystemService`) resolved up the app's own superclasses to
the platform class. Each is then resolved in android.jar to the public class that declares it
(`Activity.getSharedPreferences` is ContextWrapper's), so one API has one signature. Methods that are
not public SDK API (hidden or removed ones) are dropped.

Usage: python3 -I scripts/ml/app_api_corpus.py APK_OR_DIR... [--android-jar PATH] --out corpus.tsv
Output (TSV): class, return, name, parameters (comma-separated), apps (count), packages.
"""
import argparse
import io
import os
import struct
import sys
import zipfile
from collections import defaultdict


# ---- DEX ------------------------------------------------------------------------------------------

def uleb128(b, off):
    result, shift = 0, 0
    while True:
        x = b[off]
        off += 1
        result |= (x & 0x7F) << shift
        if x < 0x80:
            return result, off
        shift += 7


def mutf8(b, off):
    _, off = uleb128(b, off)
    end = b.index(0, off)
    return b[off:end].decode("utf-8", "replace")


class Dex:
    """The tables of one DEX file that name methods and classes."""

    def __init__(self, b):
        if b[:3] != b"dex":
            raise ValueError("not a DEX file")
        u4 = lambda o: struct.unpack_from("<I", b, o)[0]
        n_str, o_str = u4(0x38), u4(0x3C)
        n_typ, o_typ = u4(0x40), u4(0x44)
        n_pro, o_pro = u4(0x48), u4(0x4C)
        n_met, o_met = u4(0x58), u4(0x5C)
        n_cls, o_cls = u4(0x60), u4(0x64)
        self.strings = [mutf8(b, u4(o_str + 4 * i)) for i in range(n_str)]
        self.types = [self.strings[u4(o_typ + 4 * i)] for i in range(n_typ)]
        self.protos = []
        for i in range(n_pro):
            ret = self.types[u4(o_pro + 12 * i + 4)]
            p_off = u4(o_pro + 12 * i + 8)
            params = ()
            if p_off:
                size = u4(p_off)
                params = tuple(self.types[struct.unpack_from("<H", b, p_off + 4 + 2 * k)[0]] for k in range(size))
            self.protos.append((ret, params))
        self.methods = []
        for i in range(n_met):
            cls_idx, proto_idx = struct.unpack_from("<HH", b, o_met + 8 * i)
            self.methods.append((self.types[cls_idx], self.strings[u4(o_met + 8 * i + 4)], self.protos[proto_idx]))
        # Classes defined here: their superclass and the methods they declare (name, parameters).
        self.supers = {}
        self.declared = defaultdict(set)
        for i in range(n_cls):
            base = o_cls + 32 * i
            cls = self.types[u4(base)]
            sup = u4(base + 8)
            self.supers[cls] = self.types[sup] if sup != 0xFFFFFFFF else None
            data = u4(base + 24)
            if not data:
                continue
            counts = []
            off = data
            for _ in range(4):
                v, off = uleb128(b, off)
                counts.append(v)
            for _ in range(counts[0] + counts[1]):
                _, off = uleb128(b, off)
                _, off = uleb128(b, off)
            idx = 0
            for n in (counts[2], counts[3]):
                idx = 0
                for _ in range(n):
                    diff, off = uleb128(b, off)
                    _, off = uleb128(b, off)
                    _, off = uleb128(b, off)
                    idx += diff
                    _, name, (_, params) = self.methods[idx]
                    self.declared[cls].add((name, params))


def java_type(d):
    """`[Ljava/lang/String;` -> java.lang.String[]."""
    dims = len(d) - len(d.lstrip("["))
    d = d[dims:]
    prim = {"Z": "boolean", "B": "byte", "C": "char", "S": "short", "I": "int", "J": "long", "F": "float", "D": "double", "V": "void"}
    return (prim.get(d) or d[1:-1].replace("/", ".")) + "[]" * dims


# ---- android.jar ----------------------------------------------------------------------------------

def read_class(b):
    """(this, super, interfaces, public methods [(name, params, ret)], public?) of one class file."""
    r = io.BytesIO(b)
    u1 = lambda: r.read(1)[0]
    u2 = lambda: struct.unpack(">H", r.read(2))[0]
    u4 = lambda: struct.unpack(">I", r.read(4))[0]
    if u4() != 0xCAFEBABE:
        return None
    u2(); u2()
    n = u2()
    pool = [None] * n
    i = 1
    while i < n:
        tag = u1()
        if tag == 1:
            pool[i] = r.read(u2()).decode("utf-8", "replace")
        elif tag in (3, 4):
            r.read(4)
        elif tag in (5, 6):
            r.read(8)
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            pool[i] = ("ref", u2())
        elif tag in (9, 10, 11, 12, 17, 18):
            r.read(4)
        elif tag == 15:
            r.read(3)
        else:
            raise ValueError("constant tag %d" % tag)
        i += 1
    access = u2()
    name = lambda idx: pool[pool[idx][1]]
    this = name(u2())
    sup_idx = u2()
    sup = name(sup_idx) if sup_idx else None
    ifaces = [name(u2()) for _ in range(u2())]

    def skip_attrs():
        for _ in range(u2()):
            u2()
            r.read(u4())

    for _ in range(u2()):
        u2(); u2(); u2(); skip_attrs()
    methods = []
    for _ in range(u2()):
        acc, mname, desc = u2(), pool[u2()], pool[u2()]
        skip_attrs()
        if acc & 0x0001 and not mname.startswith("<") and not acc & 0x1000:
            params, ret = split_desc(desc)
            methods.append((mname, params, ret))
    return this, sup, ifaces, methods, bool(access & 0x0001)


def split_desc(desc):
    """`(ILjava/lang/String;)V` -> (("I", "Ljava/lang/String;"), "V")."""
    inner, ret = desc[1:desc.index(")")], desc[desc.index(")") + 1:]
    out, i = [], 0
    while i < len(inner):
        j = i
        while inner[j] == "[":
            j += 1
        if inner[j] == "L":
            j = inner.index(";", j)
        out.append(inner[i:j + 1])
        i = j + 1
    return tuple(out), ret


class Platform:
    """android.jar's public classes: the class that declares each public method, by class hierarchy."""

    def __init__(self, path):
        self.supers, self.ifaces, self.methods, self.public = {}, {}, {}, set()
        with zipfile.ZipFile(path) as z:
            for n in z.namelist():
                if not n.endswith(".class"):
                    continue
                try:
                    c = read_class(z.read(n))
                except Exception:
                    continue
                if c is None:
                    continue
                this, sup, ifaces, methods, public = c
                key = "L" + this + ";"
                self.supers[key] = "L" + sup + ";" if sup else None
                self.ifaces[key] = ["L" + x + ";" for x in ifaces]
                self.methods[key] = {(m, p): ret for m, p, ret in methods}
                if public:
                    self.public.add(key)

    def resolve(self, cls, name, params):
        """The public class declaring `name(params)` for a call on `cls`, with the return type; None if not public API."""
        seen, queue = set(), [cls]
        while queue:
            c = queue.pop(0)
            if c in seen or c not in self.methods:
                continue
            seen.add(c)
            ret = self.methods[c].get((name, params))
            if ret is not None and c in self.public:
                return c, ret
            if self.supers.get(c):
                queue.append(self.supers[c])
            queue.extend(self.ifaces.get(c, []))
        return None


# ---- corpus ---------------------------------------------------------------------------------------

def app_apis(apk, platform):
    """The platform methods one APK calls, as (class, return, name, params) in dotted Java names."""
    dexes = []
    with zipfile.ZipFile(apk) as z:
        for n in sorted(z.namelist()):
            if n.startswith("classes") and n.endswith(".dex"):
                dexes.append(Dex(z.read(n)))
    return apis_of(dexes, platform)


def apis_of(dexes, platform):
    """The platform methods the method references of [dexes] reach (see app_apis)."""
    defined, supers = set(), {}
    for d in dexes:
        defined.update(d.supers)
        supers.update(d.supers)
    declared = defaultdict(set)
    for d in dexes:
        for c, ms in d.declared.items():
            declared[c] |= ms
    out = set()
    for d in dexes:
        for cls, name, (ret, params) in d.methods:
            if name.startswith("<"):
                continue
            owner = cls
            # Called through an app class: the method it inherits from the first platform superclass.
            hops = 0
            while owner in defined and hops < 32:
                if (name, params) in declared[owner]:
                    owner = None
                    break
                owner = supers.get(owner)
                hops += 1
            if owner is None or owner in defined:
                continue
            hit = platform.resolve(owner, name, params)
            if hit is None:
                continue
            decl, jret = hit
            out.add((java_type(decl), java_type(jret), name, tuple(java_type(p) for p in params)))
    return out


def package_of(apk):
    """`org.example.app_42.apk` -> org.example.app (the F-Droid file name)."""
    base = os.path.basename(apk)[:-4]
    return base.rsplit("_", 1)[0] if "_" in base else base


def main():
    ap = argparse.ArgumentParser()
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    ap.add_argument("inputs", nargs="+", help="APK files or directories of APKs")
    ap.add_argument("--android-jar", default=os.path.join(root, ".tools", "android-35", "android.jar"))
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    apks = []
    for p in args.inputs:
        if os.path.isdir(p):
            apks += sorted(os.path.join(p, f) for f in os.listdir(p) if f.endswith(".apk"))
        else:
            apks.append(p)
    platform = Platform(args.android_jar)
    users = defaultdict(set)
    for apk in apks:
        try:
            apis = app_apis(apk, platform)
        except Exception as e:  # a malformed APK is reported, not fatal
            print("skipped %s: %s" % (apk, e), file=sys.stderr)
            continue
        pkg = package_of(apk)
        for s in apis:
            users[s].add(pkg)
        print("%s: %d platform methods" % (pkg, len(apis)), file=sys.stderr)
    with open(args.out, "w") as f:
        f.write("# Platform methods real apps call (scripts/ml/app_api_corpus.py): class, return, name, parameters, apps, packages\n")
        for s in sorted(users, key=lambda s: (-len(users[s]), s)):
            f.write("\t".join([s[0], s[1], s[2], ",".join(s[3]), str(len(users[s])), ",".join(sorted(users[s]))]) + "\n")
    print("%d APKs, %d distinct platform methods" % (len(apks), len(users)), file=sys.stderr)


if __name__ == "__main__":
    main()
