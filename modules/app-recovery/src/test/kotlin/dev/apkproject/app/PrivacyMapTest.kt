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
 * A small app compiled against the real android.jar, with personal data leaking thirty-four ways and
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
        "com/google/android/gms/tasks/Task.java" to """
            package com.google.android.gms.tasks;
            public class Task<T> {
                public Task<T> addOnSuccessListener(OnSuccessListener<? super T> listener) { return this; }
            }
        """.trimIndent(),
        "com/google/android/gms/tasks/OnSuccessListener.java" to "package com.google.android.gms.tasks;\npublic interface OnSuccessListener<T> { void onSuccess(T result); }\n",
        "com/google/android/gms/location/FusedLocationProviderClient.java" to """
            package com.google.android.gms.location;
            public class FusedLocationProviderClient {
                public com.google.android.gms.tasks.Task<android.location.Location> getLastLocation() { return new com.google.android.gms.tasks.Task<>(); }
            }
        """.trimIndent(),
        "androidx/lifecycle/Observer.java" to "package androidx.lifecycle;\npublic interface Observer<T> { void onChanged(T value); }\n",
        "androidx/lifecycle/LiveData.java" to """
            package androidx.lifecycle;
            public class LiveData<T> {
                public T getValue() { return null; }
                public void observe(Object owner, Observer<? super T> observer) { }
            }
        """.trimIndent(),
        "androidx/lifecycle/MutableLiveData.java" to """
            package androidx.lifecycle;
            public class MutableLiveData<T> extends LiveData<T> {
                public void postValue(T value) { }
                public void setValue(T value) { }
            }
        """.trimIndent(),
        "org/example/lib/LibEvent.java" to """
            package org.example.lib;
            public final class LibEvent {
                private final String message;
                public LibEvent(String message) { this.message = message; }
                public String getMessage() { return message; }
            }
        """.trimIndent(),
        "com/google/android/gms/ads/identifier/AdvertisingIdClient.java" to """
            package com.google.android.gms.ads.identifier;
            public class AdvertisingIdClient {
                public static Info getAdvertisingIdInfo(android.content.Context c) { return new Info(); }
                public static final class Info { public String getId() { return "00000000"; } }
            }
        """.trimIndent(),
        "com/google/gson/Gson.java" to """
            package com.google.gson;
            public class Gson {
                public <T> T fromJson(String json, Class<T> type) { return null; }
                public String toJson(Object o) { return ""; }
            }
        """.trimIndent(),
        // The app.
        "com/example/data/Message.java" to "package com.example.data;\npublic class Message { public String topic; public double lat; }\n",
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
        "com/example/data/LocationModel.java" to """
            package com.example.data;
            public final class LocationModel {
                public final androidx.lifecycle.MutableLiveData<android.location.Location> location = new androidx.lifecycle.MutableLiveData<>();
                public void update(android.location.Location l) { location.postValue(l); }
            }
        """.trimIndent(),
        "com/example/data/Prefs.java" to "package com.example.data;\npublic final class Prefs { public static String deviceId; }\n",
        "com/example/data/State.java" to "package com.example.data;\npublic final class State { public Object L${'$'}0; public String label; }\n",
        "com/example/data/FixedLocation.java" to """
            package com.example.data;
            // Keeps its own time, as PhoneTrack's CorrectingLocation does.
            public class FixedLocation extends android.location.Location {
                private long time;
                public FixedLocation(android.location.Location l) { super(l); time = l.getTime(); }
                @Override public long getTime() { return time; }
            }
        """.trimIndent(),
        "com/example/data/Sink.java" to "package com.example.data;\npublic interface Sink { void take(String s); }\n",
        "com/example/data/Quiet.java" to "package com.example.data;\npublic class Quiet implements Sink { public void take(String s) { } }\n",
        "com/example/data/Loud.java" to "package com.example.data;\npublic class Loud implements Sink { public void take(String s) { android.util.Log.d(\"loud\", s); } }\n",
        "com/example/data/Relay.java" to """
            package com.example.data;
            // Delegates to whatever its field holds; only Quiet is ever attached.
            public class Relay {
                private Sink target;
                public void attach(Sink s) { target = s; }
                public void forward(String s) { if (target != null) target.take(s); }
            }
        """.trimIndent(),
        "com/example/data/StateLogger.java" to """
            package com.example.data;
            public final class StateLogger {
                public static void log(State s) { android.util.Log.d("state", s.label); }
            }
        """.trimIndent(),
        "com/example/ipc/TrackProxy.java" to """
            package com.example.ipc;
            // What an AIDL-generated proxy does: writes the arguments into a parcel and transacts it.
            public class TrackProxy {
                private final android.os.IBinder remote;
                public TrackProxy(android.os.IBinder remote) { this.remote = remote; }
                public void send(String position) throws android.os.RemoteException {
                    android.os.Parcel data = android.os.Parcel.obtain();
                    android.os.Parcel reply = android.os.Parcel.obtain();
                    try {
                        data.writeInterfaceToken("com.example.ITrack");
                        data.writeString(position);
                        remote.transact(1, data, reply, 0);
                        reply.readException();
                    } finally { reply.recycle(); data.recycle(); }
                }
            }
        """.trimIndent(),
        "com/example/ipc/Pipes.java" to """
            package com.example.ipc;
            // What Nextcloud's single sign-on does: a thread copies a stream into a pipe whose read end a binder call hands over.
            public final class Pipes {
                public static android.os.ParcelFileDescriptor pipeFrom(java.io.InputStream in) throws java.io.IOException {
                    android.os.ParcelFileDescriptor[] pipe = android.os.ParcelFileDescriptor.createPipe();
                    new Copier(in, new android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])).start();
                    return pipe[0];
                }
                static final class Copier extends Thread {
                    private final java.io.InputStream in;
                    private final java.io.OutputStream out;
                    Copier(java.io.InputStream in, java.io.OutputStream out) { this.in = in; this.out = out; }
                    @Override public void run() {
                        byte[] buf = new byte[1024];
                        try {
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                            out.close();
                        } catch (java.io.IOException e) { android.util.Log.e("pipe", "copy failed", e); }
                    }
                }
            }
        """.trimIndent(),
        "com/example/DetailActivity.java" to """
            package com.example;
            public class DetailActivity extends android.app.Activity {
                private com.example.api.Api api;
                // The extra MainActivity.openDetails put in the intent.
                @Override protected void onCreate(android.os.Bundle saved) {
                    super.onCreate(saved);
                    api.feedback(getIntent().getStringExtra("com.example.LAT"));
                }
                // Text another app shared under the platform's key: not this app's location.
                void received() { api.feedback(getIntent().getStringExtra(android.content.Intent.EXTRA_TEXT)); }
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

                // A lambda posted to an executor, with the location it captured.
                void runInBackground(java.util.concurrent.Executor executor) {
                    Location loc = locations().getLastKnownLocation("fused");
                    executor.execute(() -> api.feedback(String.valueOf(loc.getAltitude())));
                }

                // A Play services task: the listener receives the location later.
                void lastFused(com.google.android.gms.location.FusedLocationProviderClient client) {
                    client.getLastLocation().addOnSuccessListener(l -> api.nearby(String.valueOf(l.getLatitude()), "0"));
                }

                // LiveData: a view model posts the location, the screen observes and logs it.
                private final LocationModel model = new LocationModel();
                void track() { model.update(locations().getLastKnownLocation("passive")); }
                void observeLocation() { model.location.observe(this, l -> Log.i("loc", String.valueOf(l))); }

                // SharedPreferences: saved in one method, sent from another.
                void saveToken() {
                    String id = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                    getSharedPreferences("p", 0).edit().putString("device", id).apply();
                }
                void sendSaved() { api.feedback(getSharedPreferences("p", 0).getString("device", null)); }

                // SQLite: a table written in one method, uploaded from another.
                void storeLocation() {
                    android.content.ContentValues v = new android.content.ContentValues();
                    v.put("lat", locations().getLastKnownLocation("gps").getLatitude());
                    openOrCreateDatabase("track.db", 0, null).insert("positions", null, v);
                }
                void uploadPositions() {
                    android.database.Cursor c = openOrCreateDatabase("track.db", 0, null).rawQuery("SELECT lat FROM positions", null);
                    while (c.moveToNext()) api.feedback(String.valueOf(c.getDouble(0)));
                }

                // A library class's instance field: the IMEI in one instance, another instance logged elsewhere.
                void libraryEvent() { new org.example.lib.LibEvent(phone().getImei()); }
                void otherEvent() { Log.d("event", new org.example.lib.LibEvent("started").getMessage()); }

                // A click listener that logs nothing personal.
                void listen(android.view.View button) { button.setOnClickListener(v -> Log.d("click", "clicked")); }

                // Location decides a boolean only.
                void night() {
                    Location l = locations().getLastKnownLocation("network");
                    boolean dark = l.getLatitude() > 45.0;
                    Log.d("theme", dark ? "night" : "day");
                }

                // A Callable's result is what the executor's Future returns.
                void submitted() throws Exception {
                    java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newSingleThreadExecutor();
                    java.util.concurrent.Future<String> f = ex.submit(() -> String.valueOf(locations().getLastKnownLocation("gps").getLatitude()));
                    api.feedback(f.get());
                }

                // A PrintStream over a file stores; System.out is the log.
                void export(java.io.File file) throws Exception {
                    Location l = locations().getLastKnownLocation("gps");
                    java.io.PrintStream out = new java.io.PrintStream(new java.io.FileOutputStream(file));
                    out.println(l.getLatitude());
                    System.out.println(l.getLongitude());
                }

                // The WHERE argument only selects a row: nothing about the device is stored.
                void markSynced() {
                    android.content.ContentValues v = new android.content.ContentValues();
                    v.put("synced", 1);
                    String device = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                    openOrCreateDatabase("track.db", 0, null).update("positions", v, "device = ?", new String[]{device});
                }

                // A fix's time is not where the device is, also from a subclass keeping its own.
                void fixTime() {
                    FixedLocation f = new FixedLocation(locations().getLastKnownLocation("gps"));
                    Log.d("fix", "at " + f.getTime());
                }

                // No table names the Geocoder: the privacy API model recognises the address lookup.
                void geocode(android.location.Geocoder g) throws Exception {
                    java.util.List<android.location.Address> found = g.getFromLocation(52.52, 13.40, 1);
                    api.feedback(found.get(0).getAddressLine(0));
                }

                // Binder calls: an AIDL-style proxy given the location (and elsewhere a constant), a Messenger's message.
                void syncRemote(android.os.IBinder remote) throws Exception {
                    new com.example.ipc.TrackProxy(remote).send(String.valueOf(locations().getLastKnownLocation("gps").getLatitude()));
                }
                void pingRemote(android.os.IBinder remote) throws Exception { new com.example.ipc.TrackProxy(remote).send("ping"); }
                void message(android.os.Messenger m) throws Exception {
                    m.send(android.os.Message.obtain(null, 1, locations().getLastKnownLocation("gps")));
                }

                // Intent extras: to another screen of the app under its own key, and to another app under the platform's.
                void openDetails() {
                    Intent i = new Intent(this, DetailActivity.class);
                    i.putExtra("com.example.LAT", String.valueOf(locations().getLastKnownLocation("gps").getLatitude()));
                    startActivity(i);
                }
                void shareLocation() {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.putExtra(Intent.EXTRA_TEXT, String.valueOf(locations().getLastKnownLocation("gps").getLatitude()));
                    startActivity(Intent.createChooser(i, null));
                }

                // A file written under a name, read back elsewhere by line and into a buffer; another file in the same directory.
                void saveTrack() throws Exception {
                    java.io.FileOutputStream out = openFileOutput("track.txt", 0);
                    out.write(String.valueOf(locations().getLastKnownLocation("gps").getLatitude()).getBytes());
                    out.close();
                }
                void uploadTrack() throws Exception {
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(new java.io.File(getFilesDir(), "track.txt")));
                    api.feedback(r.readLine());
                }
                void uploadTrackBytes() throws Exception {
                    java.io.FileInputStream in = openFileInput("track.txt");
                    byte[] buf = new byte[64];
                    int n = in.read(buf);
                    api.feedback(new String(buf, 0, n));
                }
                void uploadNotes() throws Exception {
                    java.io.File dir = new java.io.File(getFilesDir(), "track.txt").getParentFile();
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(new java.io.File(dir, "notes.txt")));
                    api.feedback(r.readLine());
                }

                // A request serialised in memory and copied into a pipe to another process; elsewhere a constant one.
                void pipeOut() throws Exception {
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes);
                    out.writeObject(String.valueOf(locations().getLastKnownLocation("gps").getLatitude()));
                    out.close();
                    com.example.ipc.Pipes.pipeFrom(new java.io.ByteArrayInputStream(bytes.toByteArray()));
                }
                void pipeHello() throws Exception { com.example.ipc.Pipes.pipeFrom(new java.io.ByteArrayInputStream("hello".getBytes())); }

                // The server's answers to requests that carried the location are the server's data.
                void askServer() throws Exception {
                    Location l = locations().getLastKnownLocation("gps");
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://api.example.com/near?lat=" + l.getLatitude()).openConnection();
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream()));
                    Log.d("answer", r.readLine());
                    Log.d("nearby", String.valueOf(api.nearby(String.valueOf(l.getLatitude()), "0")));
                }

                // A message queued as JSON and decoded again: re-sent as a whole; its topic (set by the app, not in the JSON) logged.
                void queue() { getSharedPreferences("q", 0).edit().putString("pending", String.valueOf(locations().getLastKnownLocation("gps").getLatitude())).apply(); }
                void resend() {
                    Message m = new com.google.gson.Gson().fromJson(getSharedPreferences("q", 0).getString("pending", null), Message.class);
                    Log.d("topic", m.topic);
                    api.feedback(new com.google.gson.Gson().toJson(m));
                }

                // Lambdas capturing `this`: one logs the activity's status, the other the location it keeps in another field.
                private Location kept;
                private String status = "idle";
                void captured() {
                    kept = locations().getLastKnownLocation("gps");
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> Log.d("status", status));
                }
                void capturedLeak() {
                    kept = locations().getLastKnownLocation("network");
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> Log.d("kept", String.valueOf(kept)));
                }

                // The relay's field only ever holds Quiet; Loud (which logs) never receives the location.
                void relay() {
                    Relay r = new Relay();
                    r.attach(new Quiet());
                    r.forward(String.valueOf(locations().getLastKnownLocation("gps").getLatitude()));
                    new Loud().take("started");
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

        val r = PrivacyMap.analyze(dex, manifest, useModel = true)
        assertFalse(r.stats.truncated)
        // D8 names a lambda's body after the method and, in newer versions, the class (`lambda${'$'}f${'$'}0${'$'}com-example-Main`).
        fun method(m: String) = m.substringAfterLast('.').replace(Regex("""\$[A-Za-z0-9_]+(?:-[A-Za-z0-9_]+)+$"""), "")
        fun key(f: PrivacyFlow) = "${f.data} -> ${f.sink}: ${f.sinkDetail} in ${method(f.sinkIn)}"
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
            "location -> NETWORK: POST api/v1/feedback in lambda\$runInBackground\$0",
            "location -> NETWORK: GET api/v1/nearby in lambda\$lastFused\$1",
            "location -> LOG: log in lambda\$observeLocation\$2",
            "device-id -> STORAGE: SharedPreferences in saveToken",
            "device-id -> NETWORK: POST api/v1/feedback in sendSaved",
            "location -> STORAGE: SQLite database in storeLocation",
            "location -> NETWORK: POST api/v1/feedback in uploadPositions",
            "location -> NETWORK: POST api/v1/feedback in submitted",
            "location -> STORAGE: file in export",
            "location -> LOG: log in export",
            "location -> NETWORK: POST api/v1/feedback in geocode",
            "location -> IPC: binder call to another app or service in send",
            "location -> IPC: message to another app or service in message",
            "location -> IPC: intent to another component in openDetails",
            "location -> IPC: intent to another component in shareLocation",
            "location -> NETWORK: POST api/v1/feedback in onCreate",
            "location -> STORAGE: file in saveTrack",
            "location -> NETWORK: POST api/v1/feedback in uploadTrack",
            "location -> NETWORK: POST api/v1/feedback in uploadTrackBytes",
            "location -> NETWORK: URL in askServer",
            "location -> NETWORK: GET api/v1/nearby in askServer",
            "location -> STORAGE: SharedPreferences in queue",
            "location -> NETWORK: POST api/v1/feedback in resend",
            "location -> IPC: pipe to another app or process in pipeFrom",
            "location -> LOG: log in lambda${'$'}capturedLeak${'$'}6",
        )
        assertEquals(expected, found, r.flows.joinToString("\n") { key(it) + "  " + it.path })
        // Only the Geocoder flow comes from the model, and it says so; without the model it is not found.
        assertEquals(setOf("geocode"), r.flows.filter { it.model != null }.map { method(it.sinkIn) }.toSet())
        assertTrue(r.flows.filter { it.model != null }.all { it.model!! >= 0.95 && ("Geocoder" in it.sourceApi || "Address" in it.sourceApi) })
        assertTrue(r.sources.any { it.model != null && it.api == "android.location.Geocoder.getFromLocation" })
        val tablesOnly = PrivacyMap.analyze(dex, manifest, useModel = false)
        assertEquals(expected - "location -> NETWORK: POST api/v1/feedback in geocode", tablesOnly.flows.map(::key).toSet())

        // Context: the clean argument of nearby() passed the same helper; only one parameter is tainted.
        val nearby = r.flows.single { it.sinkDetail == "GET api/v1/nearby" && it.sinkIn.endsWith(".sendNearby") }
        assertEquals("HIGH", nearby.attention)
        assertEquals("android.location.LocationManager.getLastKnownLocation", nearby.sourceApi)
        // The field the callback stored the location in.
        val body = r.flows.single { it.sinkIn.endsWith("onSendClicked") }
        assertEquals("com.example.MainActivity.last", body.throughField)
        assertEquals("callback onLocationChanged", body.sourceApi)
        assertEquals("com.example.data.Prefs.deviceId", r.flows.single { it.sink == "STORAGE" && it.sinkIn.endsWith(".save") }.throughField)
        // Stored and read back, and posted to LiveData: the report names the key, the table and the field.
        assertEquals("SharedPreferences key 'device'", r.flows.single { it.sinkIn.endsWith(".sendSaved") }.throughField)
        assertEquals("database table 'positions'", r.flows.single { it.sinkIn.endsWith(".uploadPositions") }.throughField)
        assertEquals("com.example.data.LocationModel.location", r.flows.single { it.sink == "LOG" && "observeLocation" in it.sinkIn }.throughField)
        assertEquals("intent or bundle extra 'com.example.LAT'", r.flows.single { it.sinkIn.endsWith("DetailActivity.onCreate") }.throughField)
        assertEquals(setOf("file 'track.txt'"), r.flows.filter { it.sinkIn.endsWith(".uploadTrack") || it.sinkIn.endsWith(".uploadTrackBytes") }.map { it.throughField }.toSet())
        assertEquals("android.os.IBinder.transact", r.flows.single { it.sinkDetail.startsWith("binder") }.sinkApi)
        // A callback's path goes through the registration and the callback method.
        assertTrue(r.flows.single { "lastFused" in it.sinkIn }.path.any { it.endsWith(".onSuccess") })
        // The IMEI needs READ_PHONE_STATE, which this manifest does not request: the flow cannot run as built.
        val imei = r.flows.single { it.sink == "IPC" && it.data == "device-id" }
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
        // No uses-sdk: minSdk 1, so every source returns data on some version; the notes say where it stops.
        assertTrue(r.flows.none { it.returnsData == false })
        assertTrue("privileged" in imei.runtime!!, imei.runtime)

        // The same app for Android 13 and later, with READ_PHONE_STATE and without the AD_ID permission: the
        // IMEI and the advertising ID return nothing on any version it supports.
        val modern = PrivacyMap.analyze(dex, useModel = true, manifestXml = manifest.replace("<application>", "<uses-sdk android:minSdkVersion=\"33\" android:targetSdkVersion=\"34\"/>\n<uses-permission android:name=\"android.permission.READ_PHONE_STATE\"/>\n<application>"))
        assertEquals(found, modern.flows.map(::key).toSet())
        val modernImei = modern.flows.single { it.sink == "IPC" && it.data == "device-id" }
        assertEquals(true, modernImei.permissionDeclared)
        assertEquals(false, modernImei.returnsData)
        assertEquals("LOW", modernImei.attention)
        assertTrue(modernImei.runtime!!.startsWith("No data on any Android version the app supports (minSdk 33)"), modernImei.runtime)
        val adId = modern.flows.filter { it.data == "advertising-id" }
        assertTrue(adId.isNotEmpty() && adId.all { it.returnsData == false && it.attention == "LOW" }, adId.toString())
        val modernSafety = modern.dataSafety.associateBy { it.data }
        assertFalse(modernSafety.getValue("advertising-id").shared, "the advertising ID is zeros")
        assertTrue(modernSafety.getValue("device-id").sinks.none { it.startsWith("IPC") }, "the IMEI is not returned")
        assertTrue(modernSafety.getValue("device-id").logged, "ANDROID_ID still is")
        assertTrue(modern.sources.filter { it.data == "advertising-id" }.all { it.returnsData == false })
        assertTrue("(no data at run time)" in PrivacyMarkdown.render(modern))
        assertTrue(modern.notes.any { it.startsWith("Run-time behaviour (minSdk 33, targetSdk 34)") }, modern.notes.toString())
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
