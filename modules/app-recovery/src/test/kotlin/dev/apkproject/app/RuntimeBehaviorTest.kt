package dev.apkproject.app

import dev.apkproject.compiler.CancellationToken
import dev.apkproject.compiler.ProcessOutcome
import dev.apkproject.contracts.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RuntimeBehaviorTest {
    @TempDir lateinit var dir: Path
    private val pkg = "dev.apkproject.runtimefixture"
    private fun request(name: String = "session", scenario: RuntimeScenario? = null): RuntimeRequest {
        val apk = dir.resolve("fixture.apk").also { Files.write(it, byteArrayOf(1, 2, 3)) }
        val adb = dir.resolve("adb").also { Files.writeString(it, "test command transport") }
        val aapt = dir.resolve("aapt2").also { Files.writeString(it, "test command transport") }
        return RuntimeRequest(apk, adb, aapt, "emulator-5566", dir.resolve(name), scenario)
    }

    /** Implements Android replies, not the engine's decisions, and journals every mutation. */
    private inner class Android : RuntimeCommand {
        val calls = arrayListOf<String>()
        var installed = false
        var launched = false
        var visible = "Result: 42"
        var physical = false
        var preexisting = false
        var sharedUid = false
        var failIpv6 = false
        var failUninstall = false
        var truncatedLogs = false
        var failStorage = false
        var failLogs = false
        var failInstallAfterCommit = false
        var failUi = false
        var cancelled: CancellationToken? = null
        var uiXml: String? = null
        val chains = linkedSetOf<String>()
        val rules = linkedSetOf<String>()

        override fun run(argv: List<String>, timeoutMs: Long, cancel: CancellationToken?): ProcessOutcome {
            assertTrue(timeoutMs > 0)
            val aapt = argv.first().endsWith("aapt2")
            val c = if (aapt) "aapt2" + if (argv.last() == "version") " version" else "" else if (argv.last() == "version") "adb version" else argv.drop(3).joinToString(" ").replace("'", "")
            calls.add(c)
            fun reply(s: String = "", code: Int = 0, truncated: Boolean = false, timeout: Boolean = false) =
                ProcessOutcome(argv, code, timeout, false, 1, s, if (code == 0) "" else "injected failure", truncated, "test")
            return when {
                c == "aapt2 version" -> reply("aapt2 fixture version")
                c == "adb version" -> reply("Android Debug Bridge fixture version")
                aapt -> reply("package: name='$pkg' versionCode='1'\nlaunchable-activity: name='$pkg.MainActivity'\n")
                c == "get-state" -> reply("device")
                c == "shell getprop ro.kernel.qemu" -> reply(if (physical) "0" else "1")
                c == "shell getprop sys.boot_completed" -> reply("1")
                c == "shell am get-current-user" -> reply("0")
                c == "shell getprop ro.build.version.sdk" -> reply("30")
                c == "shell getprop ro.build.fingerprint" -> reply("android-fixture/30/test")
                c == "shell getprop ro.product.cpu.abi" -> reply("x86_64")
                c == "root" -> reply("adbd is already running as root")
                c == "wait-for-device" -> reply()
                c == "shell id -u" -> reply("0")
                c == "shell cmd package list packages $pkg" -> reply(if (preexisting || installed) "package:$pkg\n" else "")
                c == "shell cmd package list packages -U" -> reply("package:$pkg uid:10012\n" + if (sharedUid) "package:dev.other.app uid:10012\n" else "")
                c.startsWith("shell date ") -> reply("10-11 23:12:00.000")
                c.startsWith("install ") -> { installed = true; if (failInstallAfterCommit) reply(code = 1, timeout = true) else reply("Success") }
                c.startsWith("uninstall ") -> if (failUninstall) reply(code = 1) else { installed = false; reply("Success") }
                c.contains("iptables") || c.contains("ip6tables") -> {
                    val tool = if (c.contains("ip6tables")) "ip6tables" else "iptables"
                    when {
                        c.contains(" -N ") -> { chains.add(tool); reply() }
                        c.contains(" -I OUTPUT ") -> if (failIpv6 && tool == "ip6tables") reply(code = 1) else { rules.add(tool); reply() }
                        c.contains(" -C OUTPUT ") -> reply(code = if (tool in rules) 0 else 1)
                        c.contains(" -D OUTPUT ") -> { rules.remove(tool); reply() }
                        c.contains(" -X ") -> { chains.remove(tool); reply() }
                        c.contains(" -L ") -> if (tool !in chains) reply(code = 1) else reply("Chain\n pkts bytes target\n 2 120 REJECT all -- * * 0.0.0.0/0 0.0.0.0/0\n")
                        else -> reply()
                    }
                }
                c.startsWith("shell ( ") -> if (failStorage) reply(code = 1) else reply(if (launched) "17 2 /data/user/0/$pkg/shared_prefs/result.xml\n" else "")
                c.contains("head -c 1048576") -> reply("a".repeat(64) + "  -\n")
                c.startsWith("shell stat ") -> reply("17 2")
                c.startsWith("shell am start ") -> {
                    assertEquals(setOf("iptables", "ip6tables"), rules, "Never launch before both families are denied")
                    launched = true; reply("Status: ok")
                }
                c.startsWith("shell uiautomator ") -> { if (cancelled != null) cancelled!!.cancel(); reply("UI hierarchy dumped") }
                c.startsWith("shell cat /data/local/tmp/") -> if (failUi) reply(code = 1) else reply(uiXml ?: "<hierarchy><node package='$pkg' text='$visible' class='android.widget.TextView'/><node package='$pkg' text='Calculate' class='android.widget.Button' bounds='[0,10][100,50]'/></hierarchy>")
                c.startsWith("shell input tap ") -> { visible = "Result: 44"; reply() }
                c.startsWith("shell input ") -> reply()
                c == "shell wm dismiss-keyguard" -> reply()
                c == "shell ps -A -o PID,UID,NAME" -> reply("PID UID NAME\n321 10012 $pkg\n123 9999 $pkg\n")
                c.startsWith("shell dumpsys meminfo ") -> reply("TOTAL PSS: 15320 TOTAL RSS: 28333\n")
                c.startsWith("shell cat /proc/net/") -> reply("header\n 0: 0100007F:1234 0100007F:5678 01 00000000:00000000 00:00000000 00000000 10012 0 981 1\n 1: 00000000:1234 0100007F:5678 01 00000000:00000000 00:00000000 00000000 9999 0 982 1\n")
                c.startsWith("shell logcat ") -> if (failLogs) reply(code = 1) else reply("10-11 23:12:01.100 10012 321 321 I RuntimeFixture: observed\n10-11 23:12:02.100 10012 321 321 E AndroidRuntime: FATAL EXCEPTION: main\n10-11 23:12:02.100 10012 321 321 E AndroidRuntime: java.lang.IllegalStateException: real failure\n10-11 23:12:03.100 9999 100 100 I OtherApp: private data\n", truncated = truncatedLogs)
                c.startsWith("shell dumpsys package ") -> reply("android.permission.INTERNET: granted=true\nandroid.permission.ACCESS_FINE_LOCATION: granted=false\n")
                c.startsWith("shell am force-stop ") || c.startsWith("shell rm ") -> reply()
                else -> error("Unexpected Android command: $c")
            }
        }
    }

    private fun execute(android: Android, req: RuntimeRequest = request(), token: CancellationToken = CancellationToken()) =
        RuntimeBehavior.runWith(req, token, {}, android)

    @Test fun `actual observations attributed to session and UID and app removed before firewall`() {
        val android = Android()
        val scenario = RuntimeScenario(`package` = pkg, activity = ".MainActivity", actions = listOf(RuntimeAction("snapshot", contains = "42"), RuntimeAction("tap_text", text = "Calculate"), RuntimeAction("snapshot", contains = "44")))
        val r = execute(android, request(scenario = scenario))
        assertEquals("COMPLETED", r.status)
        assertEquals(listOf(321), r.snapshots.first().processIds)
        assertEquals(15320L, r.snapshots.first().totalPssKb)
        assertEquals(4, r.snapshots.first().sockets.size)
        assertEquals("CREATED", r.fileChanges.single().change)
        assertEquals(17L, r.filesAfter.single().hashedBytes)
        assertEquals(1, r.crashExcerpts.size)
        assertFalse("private data" in r.appLog)
        assertEquals(false, r.permissions["android.permission.ACCESS_FINE_LOCATION"])
        assertEquals(2L, r.deniedPackets["IPv4"])
        assertTrue(r.cleanupComplete)
        assertFalse(android.installed)
        assertTrue(android.rules.isEmpty() && android.chains.isEmpty())
        assertTrue(android.calls.indexOfFirst { it.startsWith("uninstall ") } < android.calls.indexOfFirst { it.contains(" -D OUTPUT ") })
        assertFalse(Files.exists(dir.resolve("session/input.apk")))
        assertEquals(r, Json.decode<RuntimeBehaviorReport>(Files.readString(dir.resolve("session/report.json"))))
    }

    @Test fun `physical devices existing apps and shared UID refused`() {
        for (kind in listOf("physical", "existing", "shared")) {
            val android = Android().apply { physical = kind == "physical"; preexisting = kind == "existing"; sharedUid = kind == "shared" }
            val r = execute(android, request(kind))
            assertEquals("REFUSED", r.status)
            assertFalse(android.launched)
            if (kind != "shared") assertTrue(android.calls.none { it.startsWith("install ") || it.startsWith("uninstall ") })
            assertTrue(r.cleanupComplete)
        }
    }

    @Test fun `partial IPv6 denial failure never launches and cleans committed mutations`() {
        val android = Android().apply { failIpv6 = true }
        val r = execute(android)
        assertEquals("FAILED", r.status)
        assertFalse(android.launched)
        assertTrue(r.cleanupComplete)
        assertFalse(android.installed)
        assertTrue(android.rules.isEmpty() && android.chains.isEmpty())
    }

    @Test fun `installation timeout after remote commit still removes APK`() {
        val android = Android().apply { failInstallAfterCommit = true }
        val r = execute(android)
        assertEquals("FAILED", r.status)
        assertFalse(android.launched)
        assertFalse(android.installed)
        assertTrue(r.cleanupComplete)
    }

    @Test fun `failed uninstall retains network denial and cannot report completion`() {
        val android = Android().apply { failUninstall = true }
        val r = execute(android)
        assertEquals("FAILED", r.status)
        assertFalse(r.cleanupComplete)
        assertTrue(android.installed)
        assertEquals(setOf("iptables", "ip6tables"), android.rules)
        assertTrue(r.issues.any { "network denial rules retained" in it })
    }

    @Test fun `cancelled session still collects crash log and cleans without cancelled token`() {
        val token = CancellationToken()
        val android = Android().apply { cancelled = token }
        val r = execute(android, token = token)
        assertEquals("CANCELLED", r.status)
        assertTrue(r.cleanupComplete)
        assertFalse(android.installed)
        assertTrue(android.rules.isEmpty())
        assertEquals(1, r.crashExcerpts.size)
    }

    @Test fun `collector failure or truncation is not presented as absence of behavior`() {
        val android = Android().apply { truncatedLogs = true; failStorage = true }
        val r = execute(android)
        assertEquals("COMPLETED", r.status)
        assertEquals("PARTIAL", r.collectors.first { it.name == "App logs" }.status)
        assertContains(r.appLog, "observed")
        assertEquals("UNAVAILABLE", r.collectors.first { it.name == "Storage" }.status)
        assertTrue(r.fileChanges.isEmpty())
        assertTrue(r.issues.any { "delta withheld" in it })
        assertEquals("NOT_IMPLEMENTED", r.collectors.first { it.name == "API calls and network payloads" }.status)
    }

    @Test fun `untrusted UI cannot expand external entities or pass truncated comparison`() {
        val android = Android().apply { uiXml = "<!DOCTYPE hierarchy [<!ENTITY steal SYSTEM 'file:///etc/passwd'>]><hierarchy><node package='$pkg' text='&steal;'/></hierarchy>" }
        val r = execute(android)
        assertEquals("FAILED", r.status)
        assertTrue(r.cleanupComplete)
        assertEquals("INCONCLUSIVE", RuntimeBehavior.compare(r, r).status)
        assertFalse(Files.readString(dir.resolve("session/report.json")).contains("root:x:"))
        val deep = Android().apply { uiXml = "<hierarchy>" + "<node package='$pkg'>".repeat(140) + "</node>".repeat(140) + "</hierarchy>" }
        val bounded = execute(deep, request("deep-hierarchy"))
        assertEquals("FAILED", bounded.status)
        assertTrue(bounded.cleanupComplete)
    }

    @Test fun `comparison checks same scenario and only complete captured UI observations`() {
        val original = execute(Android(), request("original"))
        val candidate = execute(Android(), request("candidate"))
        assertEquals("SCENARIO_MATCHED", RuntimeBehavior.compare(original, candidate).status)
        val mutant = execute(Android().apply { visible = "Wrong result" }, request("mutant"))
        assertEquals("DIFFERENT", RuntimeBehavior.compare(original, mutant).status)
        assertEquals("INCONCLUSIVE", RuntimeBehavior.compare(original, candidate.copy(scenarioSha256 = "different")).status)
        assertEquals("INCONCLUSIVE", RuntimeBehavior.compare(original, candidate.copy(collectors = candidate.collectors.map { if (it.name == "UI") it.copy(status = "PARTIAL") else it })).status)
    }

    @Test fun `scenario command injection and unlimited actions rejected before device mutation`() {
        val invalid = RuntimeScenario(`package` = "dev.example; touch /tmp/x", activity = ".Main", actions = listOf(RuntimeAction("snapshot")))
        assertFailsWith<IllegalArgumentException> { execute(Android(), request(scenario = invalid)) }
        val good = invalid.copy(`package` = pkg)
        assertFailsWith<IllegalArgumentException> { RuntimeBehavior.validate(good.copy(actions = List(33) { RuntimeAction("snapshot") })) }
        assertFailsWith<IllegalArgumentException> { RuntimeBehavior.validate(good.copy(actions = listOf(RuntimeAction("text", text = "$(id)"), RuntimeAction("snapshot")))) }
        assertEquals("'O'\\''Reilly'", RuntimeBehavior.quote("O'Reilly"))
        assertFailsWith<IllegalArgumentException> { execute(Android(), request().copy(serial = "device-real")) }
        val huge = dir.resolve("huge.json").also { Files.writeString(it, " ".repeat(65_537)) }
        assertFailsWith<IllegalArgumentException> { RuntimeBehavior.readScenario(huge) }
    }

    @Test fun `UID log filtering never attributes message text or another app to target UID`() {
        val raw = "10-11 23:12:00.100 9999 7 7 I Other: 10012 private message\n" +
            "10-11 23:12:00.200 10012 8 8 I Own: result\n" +
            "10-11 23:12:00.300 root 1 1 I Root: device detail\n"
        val (kept, format) = RuntimeBehavior.filterUidLog(raw, 10012)
        assertTrue(format)
        assertContains(kept, "Own: result")
        assertFalse("private message" in kept || "device detail" in kept)
        assertFalse(RuntimeBehavior.filterUidLog("unsupported format", 10012).second)
    }
}
