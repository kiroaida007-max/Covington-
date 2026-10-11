package dev.apkproject.app

import dev.apkproject.compiler.ForwardCompiler
import dev.apkproject.contracts.CheckStatus
import dev.apkproject.contracts.Outcome
import dev.apkproject.contracts.Profiles
import dev.apkproject.dex.DexFile
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A small app compiled against the real android.jar, with personal data leaking nine ways and
 * the patterns that made the first, context-insensitive version report false flows on Tusky:
 * an identity helper shared by tainted and clean callers, two instances of a class with an
 * `Object` field, a continuation-like state object, location used only to decide a boolean, and
 * a text field reading the clipboard to paste.
 */
class PrivacyMapTest {
    @TempDir
    lateinit var dir: Path

    private val root = Paths.get(System.getProperty("apkproject.root"))

    private val sources = mapOf(
        // Library stubs (compiled into the DEX, as SDKs are in a real app).
        "retrofit2/http/GET.java" to "package retrofit2.http;\nimport java.lang.annotation.*;\n@Retention(RetentionPolicy.RUNTIME)\npublic @interface GET { String value(); }\n",
        "retrofit2/http/POST.java" to "package retrofit2.http;\nimport java.lang.annotation.*;\n@Retention(RetentionPolicy.RUNTIME)\npublic @interface POST { String value(); }\n",
        "retrofit2/http/Query.java" to "package retrofit2.http;\nimport java.lang.annotation.*;\n@Retention(RetentionPolicy.RUNTIME)\npublic @interface Query { String value(); }\n",
        "retrofit2/http/Body.java" to "package retrofit2.http;\nimport java.lang.annotation.*;\n@Retention(RetentionPolicy.RUNTIME)\npublic @interface Body { }\n",
        "okhttp3/FormBody.java" to """
            package okhttp3;
            public final class FormBody {
                public static final class Builder {
                    public Builder add(String name, String value) { return this; }
                    public FormBody build() { return new FormBody(); }
                }
            }
        """.trimIndent(),
        "com/google/firebase/analytics/FirebaseAnalytics.java" to """
            package com.google.firebase.analytics;
            public final class FirebaseAnalytics {
                public static FirebaseAnalytics getInstance(android.content.Context c) { return new FirebaseAnalytics(); }
                public void setUserId(String id) { }
            }
        """.trimIndent(),
        "com/google/android/gms/ads/identifier/AdvertisingIdClient.java" to """
            package com.google.android.gms.ads.identifier;
            public class AdvertisingIdClient {
                public static Info getAdvertisingIdInfo(android.content.Context c) { return new Info(); }
                public static final class Info { public String getId() { return "00000000"; } }
            }
        """.trimIndent(),
        // The app.
        "com/example/api/Api.java" to """
            package com.example.api;
            import retrofit2.http.*;
            public interface Api {
                @GET("api/v1/nearby") Object nearby(@Query("lat") String lat, @Query("lng") String lng);
                @POST("api/v1/feedback") Object feedback(@Body String text);
            }
        """.trimIndent(),
        "com/example/util/Util.java" to """
            package com.example.util;
            public final class Util {
                public static <T> T id(T x) { return x; }
            }
        """.trimIndent(),
        "com/example/util/Query.java" to """
            package com.example.util;
            public final class Query {
                private final StringBuilder sb = new StringBuilder();
                public Query add(String key, String value) { sb.append(key).append('=').append(value).append('&'); return this; }
                public String build() { return sb.toString(); }
            }
        """.trimIndent(),
        "com/example/data/Box.java" to "package com.example.data;\npublic class Box { public Object value; }\n",
        "com/example/data/Prefs.java" to "package com.example.data;\npublic final class Prefs { public static String deviceId; }\n",
        "com/example/data/State.java" to "package com.example.data;\npublic final class State { public Object L${'$'}0; public String label; }\n",
        "com/example/data/StateLogger.java" to """
            package com.example.data;
            public final class StateLogger {
                public static void log(State s) { android.util.Log.d("state", s.label); }
            }
        """.trimIndent(),
        "com/example/MainActivity.java" to """
            package com.example;
            import android.app.Activity;
            import android.content.ClipboardManager;
            import android.content.Context;
            import android.content.Intent;
            import android.location.Location;
            import android.location.LocationListener;
            import android.location.LocationManager;
            import android.telephony.TelephonyManager;
            import android.util.Log;
            import com.example.data.*;
            import com.example.util.Query;
            import com.example.util.Util;

            public class MainActivity extends Activity implements LocationListener {
                private com.example.api.Api api;
                private Location last;

                private LocationManager locations() { return (LocationManager) getSystemService(Context.LOCATION_SERVICE); }
                private TelephonyManager phone() { return (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE); }

                // Location through a helper into a query parameter; the other parameter passes the same helper clean.
                void sendNearby() {
                    Location loc = locations().getLastKnownLocation("gps");
                    String lat = Util.id(String.valueOf(loc.getLatitude()));
                    api.nearby(lat, Util.id("0"));
                }

                // Location into a builder that keeps it in its own StringBuilder field, then posted.
                void sendViaBuilder() {
                    Location loc = locations().getLastKnownLocation("network");
                    api.feedback(new Query().add("v", "1").add("lng", String.valueOf(loc.getLongitude())).build());
                }

                // The same builder with clean values, logged: not a flow.
                void cleanBuilder() { Log.d("q", new Query().add("a", "b").build()); }

                // The same helper with a clean value, logged: not a flow.
                void clean() { Log.d("tag", Util.id("hello")); }

                // Kept by a callback in a field, written to an HTTP request body on a click.
                @Override public void onLocationChanged(Location l) { last = l; }
                void onSendClicked() throws Exception {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://api.example.com/loc").openConnection();
                    java.io.OutputStream os = c.getOutputStream();
                    os.write(String.valueOf(last.getLongitude()).getBytes());
                }

                // ANDROID_ID logged, and kept in a static field that another method saves.
                void ids() {
                    String id = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                    Log.i("ids", "device " + id);
                    Prefs.deviceId = id;
                }
                void save() { getSharedPreferences("p", 0).edit().putString("id", Prefs.deviceId).apply(); }

                // Advertising ID handed to an analytics SDK.
                void ads() throws Exception {
                    String adId = com.google.android.gms.ads.identifier.AdvertisingIdClient.getAdvertisingIdInfo(this).getId();
                    com.google.firebase.analytics.FirebaseAnalytics.getInstance(this).setUserId(adId);
                }

                // Contact names into an OkHttp form body.
                void contacts() {
                    android.database.Cursor cur = getContentResolver().query(android.provider.ContactsContract.Contacts.CONTENT_URI, null, null, null, null);
                    while (cur.moveToNext()) {
                        String name = cur.getString(cur.getColumnIndex("display_name"));
                        new okhttp3.FormBody.Builder().add("name", name).build();
                    }
                }

                // IMEI in a broadcast any app can receive.
                void share() {
                    Intent i = new Intent("com.example.SHARE");
                    i.putExtra("imei", phone().getImei());
                    sendBroadcast(i);
                }

                // The clipboard read in the background and posted.
                void snoop() {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    api.feedback(String.valueOf(cm.getPrimaryClip().getItemAt(0).getText()));
                }

                // Two boxes with an Object field: the clean one is logged.
                void boxes() {
                    Box tainted = new Box();
                    Box clean = new Box();
                    tainted.value = phone().getImei();
                    clean.value = "clean";
                    Log.d("box", String.valueOf(clean.value));
                }

                // A continuation-like state: the IMEI in L${'$'}0, another field logged by a callee.
                void continuation() {
                    State s = new State();
                    s.L${'$'}0 = phone().getImei();
                    s.label = "start";
                    StateLogger.log(s);
                }

                // Location decides a boolean only.
                void night() {
                    Location l = locations().getLastKnownLocation("network");
                    boolean dark = l.getLatitude() > 45.0;
                    Log.d("theme", dark ? "night" : "day");
                }
            }
        """.trimIndent(),
        "com/example/ui/PasteField.java" to """
            package com.example.ui;
            import android.content.ClipboardManager;
            import android.content.Context;
            public class PasteField extends android.widget.EditText {
                public PasteField(Context c) { super(c); }
                @Override public boolean onTextContextMenuItem(int id) {
                    ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    android.util.Log.d("paste", String.valueOf(cm.getPrimaryClip().getItemAt(0).getText()));
                    return true;
                }
            }
        """.trimIndent(),
    )

    private val manifest = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example">
          <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
          <uses-permission android:name="android.permission.READ_CONTACTS"/>
          <application><activity android:name=".MainActivity" android:exported="true"/></application>
        </manifest>
    """.trimIndent()

    @Test
    fun `flows are found where data leaks and nowhere else`() {
        val profile = Profiles.load(root.resolve("profiles/android-full.json"))
        val androidJar = profile.androidJar
        assumeTrue(Files.isRegularFile(profile.d8Jar) && Files.isExecutable(profile.javac) && androidJar != null && Files.isRegularFile(androidJar), "android.jar or D8 not installed; run scripts/setup-toolchain.sh --full")
        val build = ForwardCompiler(profile).compile(sources, dir, 300_000, classpath = listOf(androidJar!!))
        assertEquals(CheckStatus.PASS, build.status, build.diagnostics)
        val dex = listOf("classes.dex" to (DexFile.parse(Files.readAllBytes(assertNotNull(build.dexFile))) as Outcome.Ok).value)

        val r = PrivacyMap.analyze(dex, manifest)
        assertFalse(r.stats.truncated)
        fun key(f: PrivacyFlow) = "${f.data} -> ${f.sink}: ${f.sinkDetail} in ${f.sinkIn.substringAfterLast('.')}"
        val found = r.flows.map(::key).toSet()
        val expected = setOf(
            "location -> NETWORK: GET api/v1/nearby in sendNearby",
            "location -> NETWORK: POST api/v1/feedback in sendViaBuilder",
            "location -> NETWORK: HTTP request body or socket in onSendClicked",
            "device-id -> LOG: log in ids",
            "device-id -> STORAGE: SharedPreferences in save",
            "advertising-id -> ANALYTICS: Firebase Analytics in ads",
            "contacts -> NETWORK: OkHttp form body in contacts",
            "device-id -> IPC: broadcast to other apps in share",
            "clipboard -> NETWORK: POST api/v1/feedback in snoop",
        )
        assertEquals(expected, found, r.flows.joinToString("\n") { key(it) + "  " + it.path })

        // Context: the clean argument of nearby() passed the same helper; only one parameter is tainted.
        val nearby = r.flows.single { it.sinkDetail == "GET api/v1/nearby" }
        assertEquals("HIGH", nearby.attention)
        assertEquals("android.location.LocationManager.getLastKnownLocation", nearby.sourceApi)
        // The field the callback stored the location in.
        val body = r.flows.single { it.sinkIn.endsWith("onSendClicked") }
        assertEquals("com.example.MainActivity.last", body.throughField)
        assertEquals("callback onLocationChanged", body.sourceApi)
        assertEquals("com.example.data.Prefs.deviceId", r.flows.single { it.sink == "STORAGE" }.throughField)
        // The IMEI needs READ_PHONE_STATE, which this manifest does not request: the flow cannot run as built.
        val imei = r.flows.single { it.sink == "IPC" }
        assertEquals(false, imei.permissionDeclared)
        assertEquals("LOW", imei.attention)
        assertEquals("android.app.Activity.sendBroadcast", imei.sinkApi, "named by the platform class, not the app class")
        // ANDROID_ID needs no permission.
        assertEquals(null, r.flows.first { it.sink == "LOG" }.permissionDeclared)

        // The clipboard read by the paste handler is not a source; the background read is.
        assertEquals(listOf("com.example.MainActivity.snoop"), r.sources.filter { it.data == "clipboard" }.flatMap { it.usedIn })
        // Data safety: location leaves the device, the ad ID is shared with an SDK, the device ID is logged and stored.
        val safety = r.dataSafety.associateBy { it.data }
        assertTrue(safety.getValue("location").collected)
        assertTrue(safety.getValue("advertising-id").shared)
        assertTrue(safety.getValue("device-id").logged && safety.getValue("device-id").stored)
        assertTrue(safety.getValue("device-id").sinks.none { it.startsWith("IPC") }, "the IMEI broadcast cannot run")
        assertEquals(true, r.capabilities.single { it.id == "location" }.permissionDeclared)
        assertEquals(false, r.capabilities.single { it.id == "phone-identifiers" }.permissionDeclared)

        val md = PrivacyMarkdown.render(r)
        assertTrue("## Data safety" in md && "GET api/v1/nearby" in md, md)
    }

    @Test
    fun `Tusky reads location and the clipboard only inside libraries and leaks neither`() {
        val apk = root.resolve(".tools/dl/tusky/tusky-142.apk")
        assumeTrue(Files.isRegularFile(apk), "Tusky 32.2 APK not downloaded (see benchmarks/privacy)")
        val input = (AppInput.open(apk) as Outcome.Ok).value
        val r = PrivacyMap.analyze(input.dexFiles(), input.decodedManifest())
        assertFalse(r.stats.truncated, r.stats.toString())
        // AppCompat's night-mode helper reads the last location; Compose's clipboard wrapper reads the clipboard.
        assertEquals(setOf("location", "clipboard"), r.sources.map { it.data }.toSet(), r.sources.toString())
        // A context-insensitive first version reported 238 flows here; none is real.
        assertEquals(emptyList(), r.flows.map { "${it.data} -> ${it.sink} ${it.sinkDetail} ${it.path}" })
        assertEquals(false, r.capabilities.single { it.id == "location" }.permissionDeclared)
    }
}
