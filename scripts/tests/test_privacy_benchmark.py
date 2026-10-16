"""The multi-app privacy benchmark (benchmarks/privacy/apps) and the API corpus extractor (scripts/ml/app_api_corpus.py)."""
import importlib.util
import os
import struct
import sys
import tempfile
import types
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
APPS = os.path.join(ROOT, "benchmarks", "privacy", "apps")


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


sys.path.insert(0, APPS)
axml = load("axml", os.path.join(APPS, "axml.py"))
evaluate = load("evaluate", os.path.join(APPS, "evaluate.py"))
corpus = load("app_api_corpus", os.path.join(ROOT, "scripts", "ml", "app_api_corpus.py"))


def manifest(permissions, utf8=True):
    """A binary AndroidManifest.xml with one <uses-permission android:name> element per permission."""
    strings = ["name", "manifest", "uses-permission"] + permissions
    data, offsets = b"", []
    for s in strings:
        offsets.append(len(data))
        if utf8:
            e = s.encode("utf-8")
            data += bytes([len(s), len(e)]) + e + b"\0"
        else:
            data += struct.pack("<H", len(s)) + s.encode("utf-16-le") + b"\0\0"
    data += b"\0" * (-len(data) % 4)
    header = 28
    start = header + 4 * len(strings)
    pool = struct.pack("<HHIIIIII", 0x0001, header, start + len(data), len(strings), 0, 0x100 if utf8 else 0, start, 0)
    pool += struct.pack("<%dI" % len(strings), *offsets) + data

    def element(tag, attrs):
        body = struct.pack("<IIIIHHHHHH", 0, 0xFFFFFFFF, 0xFFFFFFFF, tag, 20, 20, len(attrs), 0, 0, 0)
        for name, value in attrs:
            body += struct.pack("<IIIHBBI", 0xFFFFFFFF, name, value, 8, 0, 3, value)
        return struct.pack("<HHI", 0x0102, 16, 8 + len(body)) + body

    chunks = element(1, []) + b"".join(element(2, [(0, 3 + i)]) for i in range(len(permissions)))
    body = pool + chunks
    return struct.pack("<HHI", 0x0003, 8, 8 + len(body)) + body


def apk(directory, name, permissions, utf8=True):
    path = os.path.join(directory, name)
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("AndroidManifest.xml", manifest(permissions, utf8))
    return path


class ManifestTest(unittest.TestCase):
    def test_permissions(self):
        with tempfile.TemporaryDirectory() as d:
            for utf8 in (True, False):
                path = apk(d, "a%d.apk" % utf8, ["android.permission.INTERNET", "android.permission.ACCESS_FINE_LOCATION"], utf8)
                self.assertEqual(["android.permission.ACCESS_FINE_LOCATION", "android.permission.INTERNET"], axml.permissions(path))
            self.assertEqual([], axml.permissions(apk(d, "none.apk", [])))


def flow(data, sink, sink_in, source="android.location.LocationManager.getLastKnownLocation"):
    return {"data": data, "sink": sink, "sinkIn": sink_in, "sourceApi": source}


class ScoreTest(unittest.TestCase):
    verdicts = [
        {"real": True, "data": "location", "sink": "LOG", "sink_in": "a.B.log", "source": "android.location", "why": ""},
        {"real": True, "data": "location", "sink": "STORAGE", "sink_in": "a.B.save", "source": "*", "why": ""},
        {"real": False, "data": "location", "sink": "LOG", "sink_in": "a.C.flash", "source": "*", "why": ""},
    ]

    def test_verdicts_and_impossible_network(self):
        report = {"flows": [flow("location", "LOG", "a.B.log"), flow("location", "LOG", "a.C.flash"), flow("location", "NETWORK", "a.D.send")],
                  "stats": {"truncated": True, "analyses": 7}}
        s = evaluate.score(report, ["android.permission.ACCESS_FINE_LOCATION"], self.verdicts)
        self.assertEqual(1, len(s["real_found"]))
        self.assertEqual("a.B.save", s["real_missing"][0]["sink_in"])
        self.assertEqual("a.C.flash", s["false_reported"][0]["sink_in"])
        self.assertEqual(["a.D.send"], [f["sinkIn"] for f in s["impossible"]], "no INTERNET permission: a NETWORK flow cannot be real")
        self.assertFalse(s["complete"])
        with_internet = evaluate.score(report, ["android.permission.INTERNET"], self.verdicts)
        self.assertEqual([], with_internet["impossible"])
        self.assertEqual([], evaluate.score(report, None, [])["impossible"], "permissions unknown: nothing is impossible")

    def test_source_prefix(self):
        v = self.verdicts[0]
        self.assertTrue(evaluate.matches(flow("location", "LOG", "a.B.log"), v))
        self.assertFalse(evaluate.matches(flow("location", "LOG", "a.B.log", "callback onLocationChanged"), v))
        self.assertFalse(evaluate.matches(flow("location", "LOG", "a.B.logMore"), v), "the sink method is matched exactly")

    def test_checked_in_lists(self):
        apps = evaluate.read_apps(os.path.join(APPS, "apps.tsv"))
        self.assertEqual(len(apps), len({a for a, _, _ in apps}))
        self.assertTrue(all(len(sha) == 64 for _, sha, _ in apps))
        verdicts = evaluate.read_verdicts(os.path.join(APPS, "flows.tsv"))
        self.assertLessEqual(set(verdicts), {a for a, _, _ in apps}, "every verdict names a benchmark app")
        self.assertTrue(all(v["why"] for vs in verdicts.values() for v in vs), "every verdict says why")


class CorpusTest(unittest.TestCase):
    def test_types(self):
        self.assertEqual("java.lang.String[]", corpus.java_type("[Ljava/lang/String;"))
        self.assertEqual("int[][]", corpus.java_type("[[I"))
        self.assertEqual((("I", "[Ljava/lang/String;", "J"), "V"), corpus.split_desc("(I[Ljava/lang/String;J)V"))
        self.assertEqual("org.example.app", corpus.package_of("/x/org.example.app_42.apk"))
        self.assertEqual((300, 2), corpus.uleb128(bytes([0xAC, 0x02]), 0))

    def platform(self):
        p = corpus.Platform.__new__(corpus.Platform)
        p.supers = {"Landroid/app/Activity;": "Landroid/view/ContextThemeWrapper;", "Landroid/view/ContextThemeWrapper;": "Landroid/content/ContextWrapper;",
                    "Landroid/content/ContextWrapper;": "Ljava/lang/Object;", "Ljava/lang/Object;": None, "Landroid/os/Hidden;": "Ljava/lang/Object;"}
        p.ifaces = {}
        p.methods = {"Landroid/app/Activity;": {("finish", ()): "V"}, "Landroid/view/ContextThemeWrapper;": {},
                     "Landroid/content/ContextWrapper;": {("getSystemService", ("Ljava/lang/String;",)): "Ljava/lang/Object;"},
                     "Ljava/lang/Object;": {("toString", ()): "Ljava/lang/String;"}, "Landroid/os/Hidden;": {("secret", ()): "I"}}
        p.public = {"Landroid/app/Activity;", "Landroid/view/ContextThemeWrapper;", "Landroid/content/ContextWrapper;", "Ljava/lang/Object;"}
        return p

    def test_resolve_to_the_declaring_public_class(self):
        p = self.platform()
        self.assertEqual(("Landroid/content/ContextWrapper;", "Ljava/lang/Object;"), p.resolve("Landroid/app/Activity;", "getSystemService", ("Ljava/lang/String;",)))
        self.assertIsNone(p.resolve("Landroid/os/Hidden;", "secret", ()), "not public API")
        self.assertIsNone(p.resolve("Landroid/app/Activity;", "nothing", ()))

    def test_calls_through_app_classes(self):
        dex = types.SimpleNamespace(
            supers={"Lcom/a/Main;": "Landroid/app/Activity;", "Lcom/a/Base;": "Landroid/app/Activity;"},
            declared={"Lcom/a/Main;": {("finish", ())}},
            methods=[
                ("Lcom/a/Main;", "getSystemService", ("Ljava/lang/Object;", ("Ljava/lang/String;",))),  # inherited from the platform
                ("Lcom/a/Main;", "finish", ("V", ())),  # the app's own override: not a platform call
                ("Lcom/a/Base;", "<init>", ("V", ())),
                ("Landroid/app/Activity;", "finish", ("V", ())),
            ])
        apis = corpus.apis_of([dex], self.platform())
        self.assertEqual({("android.content.ContextWrapper", "java.lang.Object", "getSystemService", ("java.lang.String",)),
                          ("android.app.Activity", "void", "finish", ())}, apis)


if __name__ == "__main__":
    unittest.main()
