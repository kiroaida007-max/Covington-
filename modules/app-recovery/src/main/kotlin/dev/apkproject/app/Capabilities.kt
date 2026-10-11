package dev.apkproject.app

import kotlinx.serialization.Serializable

/** A sensitive platform capability the code uses, with the classes that use it. */
@Serializable
data class Capability(
    val id: String,
    val title: String,
    /** PRIVACY (personal data), SECURITY (code execution, integrity), DEVICE (hardware, other apps). */
    val category: String,
    /** HIGH, MEDIUM, LOW, INFO: how much a newly added use deserves review. */
    val attention: String,
    /** The framework members that showed it, as `Class.member`. */
    val apis: List<String>,
    /** Classes that call them (Java names as they appear in the APK, obfuscated or not). */
    val usedIn: List<String>,
    val reference: String,
    /** Runtime permissions the capability needs; empty when it needs none. */
    val permissions: List<String> = emptyList(),
    /**
     * Whether the manifest requests one of [permissions]; null when none is needed or the manifest
     * is unknown. False means the code is present (often unused library code) but cannot run.
     */
    val permissionDeclared: Boolean? = null,
)

/** A third-party SDK recognized in the app (analytics, ads, crash reporting, attribution, ...). */
@Serializable
data class SdkFinding(
    val name: String,
    val category: String,
    /** How it was recognized: kept package names (OBSERVED) or only its network endpoints (INFERRED). */
    val evidence: String,
    val signals: List<String>,
)

/**
 * What an app can do with the platform, read from the framework members its code calls. Framework
 * names never change under obfuscation, so this is the same for an obfuscated and a plain build.
 * It lists capabilities, not proof of misuse: a privacy review asks whether each is needed and
 * disclosed (Play "Data safety"), and an update review asks why a new one appeared.
 */
object Capabilities {
    private class Rule(val id: String, val title: String, val category: String, val attention: String, val reference: String, val members: List<String>) {
        val permissions: List<String> get() = PERMISSIONS[id].orEmpty()
    }

    /** The runtime permissions each capability needs (any one of them). */
    private val PERMISSIONS = mapOf(
        "location" to listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"),
        "contacts" to listOf("READ_CONTACTS", "WRITE_CONTACTS"),
        "call-log" to listOf("READ_CALL_LOG", "WRITE_CALL_LOG"),
        "sms" to listOf("SEND_SMS", "READ_SMS", "RECEIVE_SMS"),
        "calendar" to listOf("READ_CALENDAR", "WRITE_CALENDAR"),
        "phone-identifiers" to listOf("READ_PHONE_STATE", "READ_PHONE_NUMBERS", "READ_PRIVILEGED_PHONE_STATE"),
        "accounts" to listOf("GET_ACCOUNTS"),
        "camera" to listOf("CAMERA"),
        "microphone" to listOf("RECORD_AUDIO"),
        "bluetooth-scan" to listOf("BLUETOOTH_SCAN", "BLUETOOTH", "BLUETOOTH_ADMIN"),
        "wifi-scan" to listOf("ACCESS_WIFI_STATE"),
        "install-packages" to listOf("REQUEST_INSTALL_PACKAGES"),
        "overlay" to listOf("SYSTEM_ALERT_WINDOW"),
    )

    private val RULES = listOf(
        Rule("location", "Device location", "PRIVACY", "HIGH", "MASVS-PRIVACY-1", listOf(
            "Landroid/location/LocationManager;->requestLocationUpdates", "Landroid/location/LocationManager;->getLastKnownLocation",
            "Landroid/location/LocationManager;->getCurrentLocation", "Landroid/location/LocationManager;->requestSingleUpdate",
            "Lcom/google/android/gms/location/FusedLocationProviderClient;->",
        )),
        Rule("contacts", "Contacts", "PRIVACY", "HIGH", "MASVS-PRIVACY-1", listOf("Landroid/provider/ContactsContract")),
        Rule("call-log", "Call log", "PRIVACY", "HIGH", "MASVS-PRIVACY-1", listOf("Landroid/provider/CallLog")),
        Rule("sms", "SMS (read or send)", "PRIVACY", "HIGH", "MASVS-PRIVACY-1", listOf("Landroid/telephony/SmsManager;->send", "Landroid/provider/Telephony\$Sms")),
        Rule("calendar", "Calendar", "PRIVACY", "MEDIUM", "MASVS-PRIVACY-1", listOf("Landroid/provider/CalendarContract")),
        Rule("phone-identifiers", "Phone identifiers (IMEI, subscriber id, number)", "PRIVACY", "HIGH", "MASVS-PRIVACY-1", listOf(
            "Landroid/telephony/TelephonyManager;->getDeviceId", "Landroid/telephony/TelephonyManager;->getImei",
            "Landroid/telephony/TelephonyManager;->getSubscriberId", "Landroid/telephony/TelephonyManager;->getLine1Number",
            "Landroid/telephony/TelephonyManager;->getSimSerialNumber", "Landroid/telephony/TelephonyManager;->getMeid",
            "Landroid/os/Build;->getSerial",
        )),
        Rule("advertising-id", "Advertising ID", "PRIVACY", "MEDIUM", "MASVS-PRIVACY-1", listOf("Lcom/google/android/gms/ads/identifier/AdvertisingIdClient;->getAdvertisingIdInfo")),
        Rule("accounts", "Device accounts", "PRIVACY", "MEDIUM", "MASVS-PRIVACY-1", listOf("Landroid/accounts/AccountManager;->getAccounts")),
        Rule("installed-apps", "List of installed apps", "PRIVACY", "MEDIUM", "MASVS-PRIVACY-1", listOf(
            "Landroid/content/pm/PackageManager;->getInstalledPackages", "Landroid/content/pm/PackageManager;->getInstalledApplications",
        )),
        Rule("clipboard-read", "Reads the clipboard", "PRIVACY", "MEDIUM", "MASVS-PRIVACY-1", listOf("Landroid/content/ClipboardManager;->getPrimaryClip")),
        Rule("camera", "Camera", "PRIVACY", "MEDIUM", "MASVS-PRIVACY-1", listOf("Landroid/hardware/camera2/CameraManager;->openCamera", "Landroid/hardware/Camera;->open")),
        Rule("microphone", "Microphone (audio recording)", "PRIVACY", "HIGH", "MASVS-PRIVACY-1", listOf("Landroid/media/AudioRecord;-><init>", "Landroid/media/MediaRecorder;->setAudioSource")),
        Rule("screen-capture", "Screen capture", "PRIVACY", "HIGH", "MASVS-PRIVACY-1", listOf("Landroid/media/projection/MediaProjectionManager;->createScreenCaptureIntent", "Landroid/media/projection/MediaProjection;->createVirtualDisplay")),
        Rule("accessibility", "Accessibility service (reads other apps' screens)", "PRIVACY", "HIGH", "MASVS-PLATFORM-1", listOf("Landroid/accessibilityservice/AccessibilityService;->")),
        Rule("bluetooth-scan", "Bluetooth scanning", "DEVICE", "LOW", "MASVS-PRIVACY-1", listOf("Landroid/bluetooth/BluetoothAdapter;->startDiscovery", "Landroid/bluetooth/le/BluetoothLeScanner;->startScan")),
        Rule("wifi-scan", "Wi-Fi scan results", "PRIVACY", "MEDIUM", "MASVS-PRIVACY-1", listOf("Landroid/net/wifi/WifiManager;->getScanResults", "Landroid/net/wifi/WifiManager;->getConnectionInfo")),
        Rule("dynamic-code", "Loads code at run time (DexClassLoader)", "SECURITY", "HIGH", "MASVS-CODE-4; CWE-494", listOf(
            "Ldalvik/system/DexClassLoader;-><init>", "Ldalvik/system/InMemoryDexClassLoader;-><init>", "Ldalvik/system/PathClassLoader;-><init>",
        )),
        Rule("shell", "Runs shell commands", "SECURITY", "MEDIUM", "MASVS-CODE-4; CWE-78", listOf("Ljava/lang/Runtime;->exec", "Ljava/lang/ProcessBuilder;-><init>")),
        Rule("native-load", "Loads native libraries", "SECURITY", "LOW", "MASVS-CODE-4", listOf("Ljava/lang/System;->loadLibrary", "Ljava/lang/System;->load(", "Ljava/lang/Runtime;->loadLibrary")),
        Rule("webview-bridge", "WebView JavaScript bridge", "SECURITY", "MEDIUM", "MASVS-PLATFORM-2; CWE-749", listOf("Landroid/webkit/WebView;->addJavascriptInterface")),
        Rule("install-packages", "Installs other apps", "SECURITY", "HIGH", "MASVS-CODE-4", listOf("Landroid/content/pm/PackageInstaller;->createSession")),
        Rule("device-admin", "Device administration", "SECURITY", "HIGH", "MASVS-PLATFORM-1", listOf("Landroid/app/admin/DevicePolicyManager;->")),
        Rule("overlay", "Draws over other apps", "SECURITY", "MEDIUM", "MASVS-PLATFORM-9", listOf("Landroid/provider/Settings;->canDrawOverlays")),
        Rule("biometric", "Biometric prompt", "SECURITY", "INFO", "MASVS-AUTH-2", listOf("Landroid/hardware/biometrics/BiometricPrompt;->authenticate", "Landroidx/biometric/BiometricPrompt;->authenticate")),
        Rule("crypto", "Cryptography (Cipher)", "SECURITY", "INFO", "MASVS-CRYPTO-1", listOf("Ljavax/crypto/Cipher;->getInstance")),
        Rule("reflection", "Reflection (Method.invoke)", "SECURITY", "INFO", "MASVS-CODE", listOf("Ljava/lang/reflect/Method;->invoke")),
    )

    /**
     * Capabilities used by [classes]: each class with the framework members its code references.
     * [permissions] are the manifest's requested permissions (null if unknown).
     */
    fun of(classes: List<Pair<String, Collection<String>>>, permissions: Set<String>? = null): List<Capability> {
        val out = ArrayList<Capability>()
        for (rule in RULES) {
            val apis = LinkedHashSet<String>()
            val users = LinkedHashSet<String>()
            for ((cls, refs) in classes) for (r in refs) if (rule.members.any { r.startsWith(it) }) {
                apis += r.substringBefore('(').removePrefix("L").replace(";->", ".").replace('/', '.')
                users += cls
            }
            if (users.isEmpty()) continue
            val declared = if (rule.permissions.isEmpty() || permissions == null) null else rule.permissions.any { "android.permission.$it" in permissions }
            out += Capability(rule.id, rule.title, rule.category, rule.attention, apis.toList().take(10), users.toList().take(20), rule.reference, rule.permissions, declared)
        }
        return out
    }

    // ---- third-party SDKs ------------------------------------------------------------------

    private class Sdk(val name: String, val category: String, val packages: List<String>, val hosts: List<String> = emptyList())

    /** Well-known SDKs by package prefix and endpoint host (public knowledge, e.g. the Exodus Privacy tracker list). */
    private val SDKS = listOf(
        Sdk("Google Firebase Analytics", "Analytics", listOf("Lcom/google/firebase/analytics/", "Lcom/google/android/gms/measurement/"), listOf("app-measurement.com")),
        Sdk("Google Analytics", "Analytics", listOf("Lcom/google/android/gms/analytics/"), listOf("google-analytics.com")),
        Sdk("Amplitude", "Analytics", listOf("Lcom/amplitude/"), listOf("api2.amplitude.com", "api.amplitude.com")),
        Sdk("Mixpanel", "Analytics", listOf("Lcom/mixpanel/"), listOf("api.mixpanel.com")),
        Sdk("Segment", "Analytics", listOf("Lcom/segment/analytics/"), listOf("api.segment.io")),
        Sdk("Flurry", "Analytics", listOf("Lcom/flurry/"), listOf("data.flurry.com")),
        Sdk("Yandex AppMetrica", "Analytics", listOf("Lcom/yandex/metrica/", "Lio/appmetrica/"), listOf("appmetrica.yandex.net")),
        Sdk("Microsoft App Center", "Analytics", listOf("Lcom/microsoft/appcenter/"), listOf("in.appcenter.ms")),
        Sdk("PostHog", "Analytics", listOf("Lcom/posthog/"), listOf("app.posthog.com")),
        Sdk("Matomo", "Analytics", listOf("Lorg/matomo/")),
        Sdk("Firebase Crashlytics", "Crash reporting", listOf("Lcom/google/firebase/crashlytics/"), listOf("crashlytics.com", "firebase-settings.crashlytics.com")),
        Sdk("Sentry", "Crash reporting", listOf("Lio/sentry/"), listOf("ingest.sentry.io", "sentry.io")),
        Sdk("Bugsnag", "Crash reporting", listOf("Lcom/bugsnag/"), listOf("sessions.bugsnag.com", "notify.bugsnag.com")),
        Sdk("ACRA", "Crash reporting", listOf("Lorg/acra/")),
        Sdk("Google AdMob", "Advertising", listOf("Lcom/google/android/gms/ads/"), listOf("googleads.g.doubleclick.net", "pagead2.googlesyndication.com")),
        Sdk("Meta Audience Network", "Advertising", listOf("Lcom/facebook/ads/")),
        Sdk("AppLovin", "Advertising", listOf("Lcom/applovin/"), listOf("applovin.com")),
        Sdk("Unity Ads", "Advertising", listOf("Lcom/unity3d/ads/"), listOf("unityads.unity3d.com")),
        Sdk("ironSource", "Advertising", listOf("Lcom/ironsource/")),
        Sdk("Vungle / Liftoff", "Advertising", listOf("Lcom/vungle/")),
        Sdk("Chartboost", "Advertising", listOf("Lcom/chartboost/")),
        Sdk("InMobi", "Advertising", listOf("Lcom/inmobi/")),
        Sdk("Mintegral", "Advertising", listOf("Lcom/mbridge/")),
        Sdk("Pangle", "Advertising", listOf("Lcom/bytedance/sdk/openadsdk/")),
        Sdk("AppsFlyer", "Attribution", listOf("Lcom/appsflyer/"), listOf("appsflyer.com", "appsflyersdk.com")),
        Sdk("Adjust", "Attribution", listOf("Lcom/adjust/sdk/"), listOf("app.adjust.com")),
        Sdk("Branch", "Attribution", listOf("Lio/branch/"), listOf("api2.branch.io", "api.branch.io")),
        Sdk("Kochava", "Attribution", listOf("Lcom/kochava/")),
        Sdk("Singular", "Attribution", listOf("Lcom/singular/sdk/")),
        Sdk("Facebook SDK", "Social / analytics", listOf("Lcom/facebook/appevents/", "Lcom/facebook/login/", "Lcom/facebook/FacebookSdk;"), listOf("graph.facebook.com")),
        Sdk("Firebase Cloud Messaging", "Push", listOf("Lcom/google/firebase/messaging/")),
        Sdk("OneSignal", "Push", listOf("Lcom/onesignal/"), listOf("api.onesignal.com", "onesignal.com")),
        Sdk("UnifiedPush", "Push", listOf("Lorg/unifiedpush/")),
        Sdk("Smartlook", "Session recording", listOf("Lcom/smartlook/")),
        Sdk("UXCam", "Session recording", listOf("Lcom/uxcam/")),
        Sdk("FullStory", "Session recording", listOf("Lcom/fullstory/")),
    )

    /** SDKs present, from class names ([types]: every type descriptor in the DEX) and URL hosts. */
    fun sdks(types: Collection<String>, hosts: Collection<String>): List<SdkFinding> = SDKS.mapNotNull { s ->
        val classes = s.packages.filter { p -> types.any { it.startsWith(p) } }
        val seen = s.hosts.filter { h -> hosts.any { it == h || it.endsWith(".$h") } }
        when {
            classes.isNotEmpty() -> SdkFinding(s.name, s.category, "OBSERVED", classes.map { it.removePrefix("L").removeSuffix("/").removeSuffix(";").replace('/', '.') } + seen)
            seen.isNotEmpty() -> SdkFinding(s.name, s.category, "INFERRED", seen)
            else -> null
        }
    }
}
