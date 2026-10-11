"""Feature extraction and label rules of scripts/ml/train_api_classifier.py (the Kotlin inference mirrors them)."""
import importlib.util
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("train_api_classifier", os.path.join(HERE, "..", "ml", "train_api_classifier.py"))
try:
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
except ImportError as e:  # numpy missing
    m = None
    missing = str(e)


@unittest.skipIf(m is None, "numpy not installed")
class ApiClassifierTest(unittest.TestCase):
    def test_hash_and_words(self):
        self.assertEqual(0x4F9F2CAB, m.fnv1a("hello"))
        self.assertEqual(["get", "last", "known", "location"], m.camel("getLastKnownLocation"))
        self.assertEqual(["get", "url", "string"], m.camel("getURLString"))
        self.assertEqual(["get", "ssid"], m.camel("getSSID"))

    def test_features(self):
        f = m.features("android.location.LocationManager", "android.location.Location", "getLastKnownLocation", ("java.lang.String",))
        for t in ("p:android", "p:location", "C:locationmanager", "c:manager", "v:get", "m:known", "r:location", "R:object", "a:string", "n:1",
                  "vC:get|locationmanager", "vr:get|location", "Cm:locationmanager|getlastknownlocation", "vk:get|object"):
            self.assertIn(t, f)
        self.assertIn("R:array", m.features("android.accounts.AccountManager", "android.accounts.Account[]", "getAccounts", ()))
        self.assertIn("r:account", m.features("android.accounts.AccountManager", "android.accounts.Account[]", "getAccounts", ()))

    def test_descriptors(self):
        self.assertEqual("java.lang.String[]", m.descriptor_to_java("[Ljava/lang/String;"))
        self.assertEqual(["I", "[B", "Ljava/lang/String;"], m.split_params("I[BLjava/lang/String;"))
        self.assertEqual(("android.util.Log", "int", "d", ("java.lang.String", "java.lang.String")),
                         m.parse_sig("<android.util.Log: int d(java.lang.String,java.lang.String)> -> _SINK_"))

    def test_personal_data_rules(self):
        lm = "android.location.LocationManager"
        self.assertEqual("none", m.personal((lm, "boolean", "isProviderEnabled", ("java.lang.String",)), "source:location"))
        self.assertEqual("source:other", m.personal((lm, "java.util.List", "getProviders", ("boolean",)), "source:location"))
        self.assertEqual("none", m.personal(("android.telephony.SmsManager", "android.telephony.SmsManager", "getDefault", ()), "source:sms"))
        self.assertEqual("source:location", m.personal((lm, "android.location.Location", "getLastKnownLocation", ("java.lang.String",)), "source:location"))
        self.assertEqual("sink:log", m.personal(("android.util.Log", "int", "d", ()), "sink:log"))


if __name__ == "__main__":
    unittest.main()
