package dev.apkproject.desktop

import dev.apkproject.app.RuntimeBehavior
import dev.apkproject.app.RuntimeBehaviorReport
import dev.apkproject.app.RuntimeRequest
import dev.apkproject.compiler.CancellationToken
import dev.apkproject.contracts.isWindows
import dev.apkproject.store.AtomicFiles
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.JSplitPane
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.table.DefaultTableModel

/** A native desktop entry point for actual emulator execution, separate from static privacy. */
internal class RuntimeView(private val project: Project, private val status: StatusBar) : JPanel(BorderLayout()) {
    private val apk = JTextField(project.report?.input.orEmpty(), 40).apply { name = "runtimeApk" }
    private val adb = JTextField(sdkTool("platform-tools", "adb"), 40).apply { name = "runtimeAdb" }
    private val aapt = JTextField(sdkAapt(), 40).apply { name = "runtimeAapt2" }
    private val serial = JTextField("emulator-5554", 16).apply { name = "runtimeSerial" }
    private val scenario = JTextField(40).apply { name = "runtimeScenario" }
    private val run = JButton("Run Session").apply { name = "runtimeRun" }
    private val cancel = JButton("Cancel").apply { name = "runtimeCancel"; isEnabled = false }
    private val location = JButton("Open Session Folder").apply { isEnabled = false }
    private val state = Ui.plainLabel("No runtime session loaded.").apply { name = "runtimeState" }
    private val details = JTextArea().apply { isEditable = false; font = Ui.monospace }
    private val logs = JTextArea().apply { isEditable = false; font = Ui.monospace }
    private val scope = Ui.readOnlyText("Start a session to see which behaviors were observed and which collectors were unavailable.")
    private val observationModel = model("Action", "Elapsed", "Processes", "Memory (KiB)", "Sockets", "UI nodes")
    private val storageModel = model("Change", "Private path", "Before (bytes)", "After (bytes)")
    private val observations = ReportTable(observationModel).apply { name = "runtimeObservations"; setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION) }
    private val readers = LatestTask("runtime-report-load")
    private val pending = ArrayBlockingQueue<String>(64)
    private val ticker = Timer(100) { repeat(16) { pending.poll()?.let { state.text = it; status.message(it) } } }
    private var token: CancellationToken? = null
    private var report: RuntimeBehaviorReport? = null
    private var sessionFolder: Path? = null
    private var closed = false
    internal var running = false
        private set

    init {
        val form = JPanel(GridBagLayout()).apply { border = Ui.gap(8, 10, 8, 10) }
        var y = 0
        for ((title, field) in listOf("APK:" to apk, "ADB:" to adb, "aapt2:" to aapt, "Scenario (optional):" to scenario)) {
            form.add(JLabel(title), Ui.gbc(0, y))
            form.add(field, Ui.gbc(1, y, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
            form.add(Ui.button("Browse…") { browse(field) }, Ui.gbc(2, y++))
        }
        form.add(JLabel("Emulator:"), Ui.gbc(0, y))
        form.add(serial, Ui.gbc(1, y++, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        form.add(Ui.readOnlyText("Use a fresh rooted disposable emulator. The session installs and launches this APK, denies its IPv4/IPv6 traffic, then removes it. A blank scenario launches the main activity and takes one observation. UI text and logs stay in this project's runtime folder and may contain personal data."), Ui.gbc(0, y++, width = 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        form.add(Ui.buttonRow(location, run, cancel), Ui.gbc(0, y++, width = 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        form.add(state, Ui.gbc(0, y, width = 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        add(form, BorderLayout.NORTH)
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(observations), JScrollPane(details)).apply { resizeWeight = 0.45; dividerSize = 5 }
        val tabs = JTabbedPane().apply {
            addTab("Observations", split)
            addTab("Storage Changes", JScrollPane(ReportTable(storageModel)))
            addTab("App Log", JScrollPane(logs))
            addTab("Capture Scope", JScrollPane(scope))
        }
        add(tabs, BorderLayout.CENTER)
        run.addActionListener { start() }
        cancel.addActionListener { token?.cancel(); cancel.isEnabled = false; state.text = "Cancelling; cleaning up the emulator…" }
        location.addActionListener { sessionFolder?.let { Ui.open(this, it) } }
        observations.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showObservation() }
        loadLatest()
    }

    private fun browse(field: JTextField) {
        val chooser = JFileChooser()
        runCatching { Path.of(field.text).toFile() }.getOrNull()?.let { chooser.selectedFile = it }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) field.text = chooser.selectedFile.absolutePath
    }

    private fun start() {
        if (running || closed) return
        readers.cancel()
        val request = try {
            require(listOf(apk, adb, aapt, serial).all { it.text.isNotBlank() }) { "Select an APK, ADB, aapt2 and emulator serial." }
            RuntimeRequest(Path.of(apk.text), Path.of(adb.text), Path.of(aapt.text), serial.text.trim(),
                project.root.resolve("runtime/sessions").resolve(UUID.randomUUID().toString()),
                // Scenario parsing and filesystem work happen on the execution thread.
                scenario = null)
        } catch (e: Exception) { state.text = e.message; return }
        val scenarioPath = scenario.text.takeIf { it.isNotBlank() }
        val cancellation = CancellationToken()
        token = cancellation
        val done = CountDownLatch(1)
        val hook = Thread { cancellation.cancel(); done.await(50, TimeUnit.SECONDS) }
        Runtime.getRuntime().addShutdownHook(hook)
        running = true; run.isEnabled = false; cancel.isEnabled = true
        listOf(apk, adb, aapt, serial, scenario).forEach { it.isEnabled = false }
        state.text = "Preparing runtime session…"
        pending.clear(); ticker.start()
        Thread({
            val result = try {
                project.files.outputDirectory(request.out)
                val r = RuntimeBehavior.run(request.copy(scenario = scenarioPath?.let { RuntimeBehavior.readScenario(Path.of(it)) }), cancellation) {
                    if (!pending.offer(it)) { pending.poll(); pending.offer(it) }
                }
                AtomicFiles.writeString(project.files.directory(project.root.resolve("runtime")).resolve("latest-session.txt"), request.out.fileName.toString())
                Result.success(r)
            } catch (e: Exception) { Result.failure(e) }
            finally { done.countDown(); runCatching { Runtime.getRuntime().removeShutdownHook(hook) } }
            SwingUtilities.invokeLater {
                running = false; ticker.stop(); pending.clear(); token = null
                if (!closed) {
                    run.isEnabled = true; cancel.isEnabled = false
                    listOf(apk, adb, aapt, serial, scenario).forEach { it.isEnabled = true }
                    result.fold({ render(it, request.out) }, { state.text = "Runtime session could not start: ${it.message}" })
                }
            }
        }, "runtime-behavior-session").apply { isDaemon = false }.start()
    }

    private fun loadLatest() {
        readers.submit({
            val root = project.root.resolve("runtime")
            val pointerReader = ReportReader(root, files = project.files)
            val pointer = pointerReader.text("latest-session.txt", 64)?.trim() ?: run {
                require(pointerReader.statuses["latest-session.txt"]?.state == ReportReader.State.MISSING) { "Runtime session pointer is unreadable or blocked" }
                return@submit null
            }
            require(pointer.matches(Regex("[a-f0-9-]{36}"))) { "Invalid runtime session pointer" }
            val folder = root.resolve("sessions").resolve(pointer)
            val reader = ReportReader(folder, files = project.files)
            val r = reader.read("report.json", RuntimeBehaviorReport.serializer())
                ?: throw IllegalArgumentException(reader.statuses["report.json"]?.detail ?: "Runtime report unavailable")
            validateDisplayReport(r)
            r to folder
        }, { if (!closed && !running) it?.let { (r, folder) -> render(r, folder) } }, { state.text = "Runtime report unavailable: ${it.message}" })
    }

    internal fun render(r: RuntimeBehaviorReport, folder: Path) {
        validateDisplayReport(r)
        report = r; sessionFolder = folder; location.isEnabled = true
        state.text = "${r.status}: ${r.actionsCompleted} actions, ${r.snapshots.size} observations, ${r.crashExcerpts.size} crash excerpts. Cleanup: ${if (r.cleanupComplete) "complete" else "FAILED"}."
        observationModel.rowCount = 0
        r.snapshots.forEach { observationModel.addRow(arrayOf(it.action + 1, "${it.elapsedMs} ms", it.processIds.joinToString(), it.totalPssKb ?: "unavailable", it.sockets.size, it.ui.size)) }
        storageModel.rowCount = 0
        r.fileChanges.forEach { storageModel.addRow(arrayOf(it.change, it.after?.path ?: it.before?.path, it.before?.bytes ?: "", it.after?.bytes ?: "")) }
        logs.text = r.appLog; logs.caretPosition = 0
        scope.text = RuntimeBehavior.markdown(r)
        details.text = r.failure ?: "Select an observation to inspect its visible UI and UID-attributed socket metadata."
        if (r.snapshots.isNotEmpty()) observations.setRowSelectionInterval(0, 0)
        status.message(state.text)
    }

    private fun showObservation() {
        val snapshot = report?.snapshots?.getOrNull(observations.selectedRow) ?: return
        details.text = buildString {
            append("Observed UI at action ${snapshot.action + 1} (${snapshot.elapsedMs} ms)\n")
            snapshot.ui.forEach { append(it.attributes.entries.filter { e -> e.value.isNotEmpty() }.joinToString("  ") { e -> "${e.key}: ${e.value}" }).append('\n') }
            append("\nUID-attributed socket metadata (kernel hexadecimal):\n")
            snapshot.sockets.forEach { append("${it.protocol} ${it.local} -> ${it.remote} state=${it.state}\n") }
        }
        details.caretPosition = 0
    }

    fun dispose() { closed = true; token?.cancel(); ticker.stop(); readers.close() }

    companion object {
        /** Imported reports must respect capture bounds before they populate Swing models. */
        internal fun validateDisplayReport(r: RuntimeBehaviorReport) {
            require(r.schemaVersion == 1 && r.evidence == "OBSERVED" && r.status in setOf("COMPLETED", "FAILED", "REFUSED", "CANCELLED")) { "Incompatible runtime report" }
            require(r.snapshots.size <= 32 && r.filesBefore.size <= 128 && r.filesAfter.size <= 128 && r.fileChanges.size <= 256) { "Runtime report exceeds capture record limits" }
            var uiCharacters = 0L
            r.snapshots.forEach { s ->
                require(s.ui.size <= 500 && s.processIds.size <= 64 && s.sockets.size <= 128) { "Runtime observation exceeds capture limits" }
                s.ui.forEach { n ->
                    require(n.attributes.size <= 16 && n.attributes.all { (k, v) -> k.length <= 64 && v.length <= 256 }) { "Runtime UI fields exceed capture limits" }
                    uiCharacters += n.attributes.values.sumOf { it.length }.toLong()
                }
                require(s.sockets.all { it.protocol.length <= 8 && it.local.length <= 80 && it.remote.length <= 80 && it.state.length <= 4 }) { "Runtime socket fields exceed capture limits" }
            }
            require(uiCharacters <= 256 * 1024 && r.appLog.length <= 64 * 1024 && r.crashExcerpts.size <= 16 && r.crashExcerpts.all { it.length <= 2048 }) { "Runtime text exceeds capture limits" }
            require(r.collectors.size <= 16 && r.collectors.all { it.name.length <= 128 && it.status.length <= 32 && it.detail.length <= 4096 } && r.issues.size <= 64 && r.issues.all { it.length <= 1024 }) { "Runtime coverage exceeds capture limits" }
            require(r.scope.length <= 4096 && (r.failure?.length ?: 0) <= 8192 && (r.packageName?.length ?: 0) <= 200 && (r.activity?.length ?: 0) <= 200 && r.serial.length <= 32 && (r.apkSha256?.length ?: 0) <= 64 && (r.scenarioSha256?.length ?: 0) <= 64) { "Runtime identity fields exceed capture limits" }
            val files = r.filesBefore + r.filesAfter + r.fileChanges.flatMap { listOfNotNull(it.before, it.after) }
            require(files.all { it.path.length <= 512 && (it.prefixSha256?.length ?: 0) <= 64 }) { "Runtime storage fields exceed capture limits" }
        }

        private fun model(vararg columns: String) = object : DefaultTableModel(columns, 0) { override fun isCellEditable(row: Int, column: Int) = false }
        private fun sdk(): Path? = (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))?.let(Path::of)
        private fun sdkTool(dir: String, name: String) = sdk()?.resolve(dir)?.resolve(name + if (isWindows()) ".exe" else "")?.toString().orEmpty()
        private fun sdkAapt(): String {
            val root = sdk()?.resolve("build-tools") ?: return ""
            if (!Files.isDirectory(root)) return ""
            return Files.newDirectoryStream(root).use { dirs -> dirs.take(64).sortedDescending().map { it.resolve(if (isWindows()) "aapt2.exe" else "aapt2") }.firstOrNull { Files.isRegularFile(it) }?.toString().orEmpty() }
        }
    }
}
