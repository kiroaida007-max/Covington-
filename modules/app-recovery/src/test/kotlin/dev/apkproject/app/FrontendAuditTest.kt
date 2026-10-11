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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FrontendAuditTest {
    @TempDir
    lateinit var dir: Path

    private val manifest = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
            <uses-sdk android:minSdkVersion="19" android:targetSdkVersion="26"/>
            <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
            <uses-permission android:name="android.permission.INTERNET"/>
            <application android:debuggable="true" android:allowBackup="true" android:usesCleartextTraffic="true">
                <activity android:name="com.example.app.MainActivity">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN"/>
                        <category android:name="android.intent.category.LAUNCHER"/>
                    </intent-filter>
                </activity>
                <activity android:name="com.example.app.ShareActivity" android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.SEND"/>
                        <data android:scheme="https" android:host="example.com"/>
                    </intent-filter>
                </activity>
                <provider android:name="com.example.app.FilesProvider" android:authorities="com.example.app.files"
                          android:exported="true" android:grantUriPermissions="true"/>
            </application>
        </manifest>
    """.trimIndent()

    /** Writes a sample manifest, layout and strings resource under a temp project tree. */
    private fun writeProject(): Path {
        val main = Files.createDirectories(dir.resolve("app/src/main"))
        Files.writeString(main.resolve("AndroidManifest.xml"), manifest)
        val layout = Files.createDirectories(main.resolve("res/layout"))
        Files.writeString(
            layout.resolve("activity_main.xml"),
            """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:layout_width="match_parent" android:layout_height="match_parent">
                <ImageView android:layout_width="24px" android:layout_height="24px"/>
                <TextView android:text="Hello world" android:layout_width="wrap_content" android:layout_height="wrap_content"/>
                <EditText android:layout_width="match_parent" android:layout_height="wrap_content"/>
                <ImageButton android:layout_width="32dp" android:layout_height="32dp" android:onClick="go"/>
            </LinearLayout>
            """.trimIndent(),
        )
        val values = Files.createDirectories(main.resolve("res/values"))
        Files.writeString(
            values.resolve("strings.xml"),
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">Example</string>
                <string name="maps_key">AIzaKEY_PLACEHOLDER_35_CHARS_0123456789</string>
            </resources>
            """.trimIndent(),
        )
        return main
    }

    /** Builds a minimal AppAnatomy carrying only the given framework findings. */
    private fun anatomy(frameworks: List<FrameworkFinding>) =
        AppAnatomy("APK", emptyList(), emptyList(), 0, 0, frameworks, emptyList(), emptyList(), emptyList(), emptyList())

    /** A manifest with multiple issues plus layouts and resources yields findings, a risk grade and a toolkit. */
    @Test
    fun `manifest, layouts and resources yield findings, a risk score and the toolkit`() {
        val main = writeProject()
        val java = listOf(FrameworkFinding("java", "Java", Recoverability.DECOMPILABLE, listOf("DEX"), "note"))
        val report = FrontendAudit.analyze(main, manifest, emptyList(), anatomy(java))

        assertEquals("Android Views (XML layouts)", report.toolkit.name)
        assertEquals("Java", report.toolkit.language)

        // Screens: both activities, the launcher flagged, the exported one, and the layout.
        val screens = report.screens.associateBy { it.name + ":" + it.kind }
        assertTrue(screens["MainActivity:ACTIVITY"]!!.launcher)
        assertTrue(screens["ShareActivity:ACTIVITY"]!!.exported)
        assertEquals(listOf("https"), screens["ShareActivity:ACTIVITY"]!!.deepLinks)
        assertTrue(report.screens.any { it.kind == "LAYOUT" && it.name == "activity_main" })

        val byId = report.findings.associateBy { it.id }
        assertEquals("CRITICAL", byId.getValue("debuggable").severity)
        assertEquals("HIGH", byId.getValue("cleartext").severity)
        assertEquals("MEDIUM", byId.getValue("allow-backup").severity)
        assertEquals("MEDIUM", byId.getValue("low-min-sdk").severity)
        assertEquals("HIGH", byId.getValue("exported-provider:FilesProvider").severity)
        assertNotNull(byId["exported-activity"], "ShareActivity is exported without a permission")
        assertEquals("LOW", byId.getValue("deep-link-unverified").severity)
        // The launcher is not reported as an exported-component risk.
        assertTrue(byId["exported-activity"]!!.where.contains("ShareActivity"))

        // Secret in resources (OBSERVED), accessibility and localization (layout).
        assertEquals("HIGH", byId.getValue("secret:google-api-key").severity)
        assertEquals("OBSERVED", byId.getValue("secret:google-api-key").evidence)
        assertEquals("MEDIUM", byId.getValue("a11y-content-description").severity)
        assertTrue(byId.getValue("i18n-hardcoded-text").count >= 1)
        assertNotNull(byId["ui-px-dimension"])
        assertNotNull(byId["ui-no-input-type"])
        assertEquals("INFO", byId.getValue("dangerous-permissions").severity)

        // A critical finding forces grade F; every finding carries a reference.
        assertEquals("F", report.risk.grade)
        assertTrue(report.risk.score in 1..100)
        assertTrue(report.findings.all { it.reference.isNotBlank() && it.fix.isNotBlank() })
    }

    /** A manifest with no risky settings produces no critical or high findings and grade A. */
    @Test
    fun `a clean manifest scores grade A`() {
        val main = Files.createDirectories(dir.resolve("clean/app/src/main"))
        Files.writeString(
            main.resolve("AndroidManifest.xml"),
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.clean">
                <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34"/>
                <application android:allowBackup="false" android:usesCleartextTraffic="false">
                    <activity android:name="com.example.clean.MainActivity" android:exported="true">
                        <intent-filter><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
                    </activity>
                </application>
            </manifest>
            """.trimIndent(),
        )
        val report = FrontendAudit.analyze(main, Files.readString(main.resolve("AndroidManifest.xml")), emptyList(), anatomy(emptyList()))
        assertEquals(0, report.risk.critical)
        assertEquals(0, report.risk.high)
        assertEquals("A", report.risk.grade)
    }

    /** Risky WebView API calls (JS interface, file access, debugging) are detected from compiled bytecode. */
    @Test
    fun `WebView API misuse is found in the bytecode`() {
        val profile = Profiles.load(Paths.get(System.getProperty("apkproject.root")).resolve("profiles/v0-jvm-pure.json"))
        assumeTrue(Files.isRegularFile(profile.d8Jar) && Files.isExecutable(profile.javac), "toolchain not installed")
        // Stub WebView-like methods so the method references exist in the DEX; the audit scans names.
        val sources = mapOf(
            "web/WebView.java" to "package web;\npublic class WebView { public void addJavascriptInterface(Object o, String n) {} public void loadUrl(String u) {} public void setWebContentsDebuggingEnabled(boolean b) {} public void setAllowUniversalAccessFromFileURLs(boolean b) {} }\n",
            "web/App.java" to "package web;\npublic class App { void open(WebView w) { w.addJavascriptInterface(this, \"bridge\"); w.setAllowUniversalAccessFromFileURLs(true); w.setWebContentsDebuggingEnabled(true); w.loadUrl(\"https://x\"); } }\n",
        )
        val build = ForwardCompiler(profile).compile(sources, dir.resolve("web-build"), 300_000)
        assertEquals(CheckStatus.PASS, build.status, build.diagnostics)
        val dex = listOf("classes.dex" to (DexFile.parse(Files.readAllBytes(assertNotNull(build.dexFile))) as Outcome.Ok).value)

        val main = Files.createDirectories(dir.resolve("web/app/src/main"))
        val report = FrontendAudit.analyze(main, "<manifest><uses-sdk android:targetSdkVersion=\"15\"/></manifest>", dex, anatomy(emptyList()))
        val byId = report.findings.associateBy { it.id }
        // targetSdk 15 (<17) makes the JavaScript interface finding critical.
        assertEquals("CRITICAL", byId.getValue("webview-js-interface").severity)
        assertEquals("INFERRED", byId.getValue("webview-js-interface").evidence)
        assertEquals("HIGH", byId.getValue("webview-file-access").severity)
        assertNotNull(byId["webview-debugging"])
    }
}
