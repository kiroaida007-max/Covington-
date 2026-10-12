"""Checks the SDK-annotation parser behind platform-permissions.tsv."""
import importlib.util
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SPEC = importlib.util.spec_from_file_location('platform_permissions', os.path.join(HERE, '..', 'privacy', 'platform_permissions.py'))
pp = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pp)

XML = '''<root>
  <item name="android.location.LocationManager android.location.Location getLastKnownLocation(java.lang.String)">
    <annotation name="androidx.annotation.RequiresPermission">
      <val name="anyOf" val="{&quot;android.permission.ACCESS_COARSE_LOCATION&quot;, &quot;android.permission.ACCESS_FINE_LOCATION&quot;}" />
    </annotation>
  </item>
  <item name="android.accounts.AccountManager android.accounts.Account[] getAccounts()">
    <annotation name="androidx.annotation.RequiresPermission">
      <val name="value" val="&quot;android.permission.GET_ACCOUNTS&quot;" />
      <val name="apis" val="&quot;..22&quot;" />
    </annotation>
  </item>
  <item name="android.net.wifi.WifiManager java.util.List&lt;android.net.wifi.WifiConfiguration&gt; getConfiguredNetworks()">
    <annotation name="androidx.annotation.RequiresPermission">
      <val name="allOf" val="{&quot;android.permission.ACCESS_FINE_LOCATION&quot;, &quot;android.permission.ACCESS_WIFI_STATE&quot;}" />
      <val name="conditional" val="true" />
    </annotation>
  </item>
  <item name="android.location.LocationManager ACTION_X">
    <annotation name="androidx.annotation.RequiresPermission"><val name="value" val="&quot;android.permission.X&quot;" /></annotation>
  </item>
  <item name="android.util.Log int d(java.lang.String, java.lang.String)">
    <annotation name="androidx.annotation.NonNull" />
  </item>
</root>
'''


class PlatformPermissionsTest(unittest.TestCase):
    def test_parse(self):
        with tempfile.TemporaryDirectory() as d:
            os.makedirs(os.path.join(d, 'android', 'location'))
            with open(os.path.join(d, 'android', 'location', 'annotations.xml'), 'w') as f:
                f.write(XML)
            rows = pp.parse(d)
        self.assertEqual(rows[('android.location.LocationManager', 'getLastKnownLocation', 'java.lang.String')],
                         ('anyOf', 'ACCESS_COARSE_LOCATION,ACCESS_FINE_LOCATION', '', ''))
        self.assertEqual(rows[('android.accounts.AccountManager', 'getAccounts', '')], ('anyOf', 'GET_ACCOUNTS', '..22', ''))
        self.assertEqual(rows[('android.net.wifi.WifiManager', 'getConfiguredNetworks', '')],
                         ('allOf', 'ACCESS_FINE_LOCATION,ACCESS_WIFI_STATE', '', 'true'))
        # A field (no parameter list) and an item without RequiresPermission are not rows.
        self.assertEqual(len(rows), 3)

    def test_generics_and_params(self):
        self.assertEqual(pp.split_params('java.util.Map<java.lang.String,java.lang.Integer>, int'), ['java.util.Map<java.lang.String,java.lang.Integer>', 'int'])
        self.assertEqual(pp.strip_generics('java.util.List<android.net.wifi.ScanResult>'), 'java.util.List')


if __name__ == '__main__':
    unittest.main()
