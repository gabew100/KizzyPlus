package dev.kizzyplus.companion.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.kizzyplus.companion.Diagnostics
import dev.kizzyplus.companion.GameNames
import dev.kizzyplus.companion.R
import dev.kizzyplus.companion.Settings
import dev.kizzyplus.companion.StatusBus
import dev.kizzyplus.companion.discord.DiscordApi
import dev.kizzyplus.companion.discord.KizzyImport
import dev.kizzyplus.companion.discord.KizzyServices
import dev.kizzyplus.companion.discord.TokenStore
import dev.kizzyplus.companion.root.RootShell
import dev.kizzyplus.companion.service.PresenceService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var io: ExecutorService

    private lateinit var nowTitle: TextView
    private lateinit var nowDetail: TextView
    private lateinit var nowDiscord: TextView
    private lateinit var problem: TextView
    private lateinit var kizzyWarning: TextView
    private lateinit var clearStatus: Button
    private lateinit var renameGame: Button
    private lateinit var namesList: LinearLayout
    private lateinit var names: GameNames
    private lateinit var enableSwitch: Switch
    private lateinit var rootStatus: TextView
    private lateinit var rootRetry: Button
    private lateinit var accountStatus: TextView
    private lateinit var accountButtons: LinearLayout
    private lateinit var signInWeb: Button
    private lateinit var signInKizzy: Button
    private lateinit var signInPaste: Button
    private lateinit var signOut: Button
    private lateinit var backendGroup: RadioGroup
    private lateinit var backendNote: TextView
    private lateinit var watchGameNative: Switch
    private lateinit var watchEden: Switch
    private lateinit var pollSpinner: Spinner
    private lateinit var artGroup: RadioGroup
    private lateinit var showLauncher: Switch
    private lateinit var statusSpinner: Spinner
    private lateinit var debugRun: Button
    private lateinit var debugCopy: Button
    private lateinit var debugOutput: TextView
    private lateinit var appId: EditText
    private lateinit var extraGameNative: EditText
    private lateinit var extraEden: EditText

    private lateinit var pollValues: IntArray
    private lateinit var statusValues: Array<String>
    private var binding = false
    private var report: String? = null
    private var accountBusy = false

    private val statusListener: (StatusBus.Status) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        io = Executors.newSingleThreadExecutor()

        nowTitle = findViewById(R.id.nowTitle)
        nowDetail = findViewById(R.id.nowDetail)
        nowDiscord = findViewById(R.id.nowDiscord)
        problem = findViewById(R.id.problem)
        kizzyWarning = findViewById(R.id.kizzyWarning)
        clearStatus = findViewById(R.id.clearStatus)
        renameGame = findViewById(R.id.renameGame)
        namesList = findViewById(R.id.namesList)
        names = GameNames(this)
        enableSwitch = findViewById(R.id.enableSwitch)
        rootStatus = findViewById(R.id.rootStatus)
        rootRetry = findViewById(R.id.rootRetry)
        accountStatus = findViewById(R.id.accountStatus)
        accountButtons = findViewById(R.id.accountButtons)
        signInWeb = findViewById(R.id.signInWeb)
        signInKizzy = findViewById(R.id.signInKizzy)
        signInPaste = findViewById(R.id.signInPaste)
        signOut = findViewById(R.id.signOut)
        backendGroup = findViewById(R.id.backendGroup)
        backendNote = findViewById(R.id.backendNote)
        watchGameNative = findViewById(R.id.watchGameNative)
        watchEden = findViewById(R.id.watchEden)
        pollSpinner = findViewById(R.id.pollSpinner)
        artGroup = findViewById(R.id.artGroup)
        showLauncher = findViewById(R.id.showLauncher)
        statusSpinner = findViewById(R.id.statusSpinner)
        debugRun = findViewById(R.id.debugRun)
        debugCopy = findViewById(R.id.debugCopy)
        debugOutput = findViewById(R.id.debugOutput)
        appId = findViewById(R.id.appId)
        extraGameNative = findViewById(R.id.extraGameNative)
        extraEden = findViewById(R.id.extraEden)

        pollValues = resources.getIntArray(R.array.poll_values)
        statusValues = resources.getStringArray(R.array.status_values)
        pollSpinner.adapter = spinnerAdapter(R.array.poll_labels)
        statusSpinner.adapter = spinnerAdapter(R.array.status_labels)

        wireListeners()
    }

    override fun onStart() {
        super.onStart()
        bindSettings()
        renderNames()
        StatusBus.listen(statusListener)
        refreshAccount()
        checkRoot()
        checkKizzy()
        if (settings.enabled && !PresenceService.running) PresenceService.start(this)
    }

    override fun onStop() {
        StatusBus.unlisten(statusListener)
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Framework Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_LOGIN && resultCode == RESULT_OK) {
            data?.getStringExtra(LoginActivity.EXTRA_TOKEN)?.let(::verifyAndSave)
        }
    }

    private fun wireListeners() {
        enableSwitch.setOnCheckedChangeListener { _, on ->
            if (binding) return@setOnCheckedChangeListener
            settings.enabled = on
            if (on) {
                askForNotifications()
                PresenceService.start(this)
            } else {
                PresenceService.stop(this)
                // Turning sharing off can't stop Kizzy's own modes; say so if one is still running.
                checkKizzy()
            }
            render(StatusBus.current)
        }

        clearStatus.setOnClickListener { clearDiscordStatus() }

        renameGame.setOnClickListener {
            val status = StatusBus.current
            val key = status.gameKey ?: return@setOnClickListener
            askForName(key, status.detectedTitle ?: status.headline, status.headline)
        }

        rootRetry.setOnClickListener {
            RootShell.retry()
            checkRoot()
            PresenceService.refresh(this)
        }

        signInWeb.setOnClickListener {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, LoginActivity::class.java), REQUEST_LOGIN)
        }
        signInKizzy.setOnClickListener { importFromKizzy() }
        signInPaste.setOnClickListener { askForToken() }
        signOut.setOnClickListener {
            TokenStore.clear(this)
            refreshAccount()
            PresenceService.refresh(this)
        }

        backendGroup.setOnCheckedChangeListener { _, id ->
            if (binding) return@setOnCheckedChangeListener
            settings.backend = if (id == R.id.backendKizzy) Settings.BACKEND_KIZZY else Settings.BACKEND_BUILTIN
            updateBackendNote()
            refreshAccount()
            PresenceService.refresh(this)
        }

        watchGameNative.setOnCheckedChangeListener { _, on ->
            if (binding) return@setOnCheckedChangeListener
            settings.watchGameNative = on
            PresenceService.refresh(this)
        }
        watchEden.setOnCheckedChangeListener { _, on ->
            if (binding) return@setOnCheckedChangeListener
            settings.watchEden = on
            PresenceService.refresh(this)
        }
        pollSpinner.onItemSelectedListener = onSelected { position ->
            val seconds = pollValues[position]
            if (seconds != settings.pollSeconds) {
                settings.pollSeconds = seconds
                PresenceService.refresh(this)
            }
        }

        artGroup.setOnCheckedChangeListener { _, id ->
            if (binding) return@setOnCheckedChangeListener
            settings.artwork = if (id == R.id.artHeader) Settings.ART_HEADER else Settings.ART_COVER
            PresenceService.refresh(this)
        }
        showLauncher.setOnCheckedChangeListener { _, on ->
            if (binding) return@setOnCheckedChangeListener
            settings.showLauncher = on
            PresenceService.refresh(this)
        }
        statusSpinner.onItemSelectedListener = onSelected { position ->
            val value = statusValues[position]
            if (value != settings.status) {
                settings.status = value
                PresenceService.refresh(this)
            }
        }

        debugRun.setOnClickListener { runCheck() }
        debugCopy.setOnClickListener {
            val text = report ?: return@setOnClickListener
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Kizzy+ check", text))
            // Android 13+ shows its own clipboard confirmation.
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.debug_copied, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.advancedSave).setOnClickListener { saveAdvanced() }
    }

    private fun bindSettings() {
        binding = true
        enableSwitch.isChecked = settings.enabled
        backendGroup.check(if (settings.backend == Settings.BACKEND_KIZZY) R.id.backendKizzy else R.id.backendBuiltin)
        watchGameNative.isChecked = settings.watchGameNative
        watchEden.isChecked = settings.watchEden
        pollSpinner.setSelection(pollValues.indexOf(settings.pollSeconds).coerceAtLeast(0))
        artGroup.check(if (settings.artwork == Settings.ART_HEADER) R.id.artHeader else R.id.artCover)
        showLauncher.isChecked = settings.showLauncher
        statusSpinner.setSelection(statusValues.indexOf(settings.status).coerceAtLeast(0))
        appId.setText(if (settings.applicationId == Settings.DEFAULT_APPLICATION_ID) "" else settings.applicationId)
        appId.hint = Settings.DEFAULT_APPLICATION_ID
        extraGameNative.setText(settings.extraGameNative)
        extraEden.setText(settings.extraEden)
        binding = false
        updateBackendNote()
    }

    private fun render(status: StatusBus.Status) {
        renameGame.visibility =
            if (settings.enabled && status.running && status.gameKey != null) View.VISIBLE else View.GONE
        when {
            !settings.enabled -> {
                nowTitle.text = getString(R.string.now_service_off)
                nowDetail.text = getString(R.string.now_service_off_detail)
                nowDiscord.visibility = View.GONE
                problem.visibility = View.GONE
            }
            !status.running -> {
                nowTitle.text = getString(R.string.now_starting)
                nowDetail.text = ""
                nowDiscord.visibility = View.GONE
                problem.visibility = View.GONE
            }
            else -> {
                nowTitle.text = status.headline
                nowDetail.text = status.detail
                nowDiscord.text = getString(R.string.now_discord, status.discord)
                nowDiscord.visibility = View.VISIBLE
                problem.text = status.problem
                problem.visibility = if (status.problem.isNullOrBlank()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun checkRoot() {
        rootStatus.text = getString(R.string.root_checking)
        rootRetry.visibility = View.GONE
        io.execute {
            val ok = RootShell.isRootAvailable()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                rootStatus.text = getString(if (ok) R.string.root_ok else R.string.root_missing)
                rootRetry.visibility = if (ok) View.GONE else View.VISIBLE
            }
        }
    }

    /** Asks for the name to show for a game; an empty name or the detected one removes the saved name. */
    private fun askForName(key: String, detected: String, current: String) {
        val field = EditText(this).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(InputFilter.LengthFilter(GameNames.MAX_LENGTH))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(current)
            selectAll()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val frame = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setMessage(getString(R.string.rename_message, detected))
            .setView(frame)
            .setPositiveButton(R.string.rename_save) { _, _ ->
                val name = field.text.toString().trim()
                if (name.isEmpty() || name == detected) names.remove(key) else names.set(key, name, detected)
                onNamesChanged()
            }
            .setNegativeButton(R.string.cancel, null)
        if (names.get(key) != null) {
            builder.setNeutralButton(R.string.rename_reset) { _, _ ->
                names.remove(key)
                onNamesChanged()
            }
        }
        builder.show()
        field.requestFocus()
    }

    private fun onNamesChanged() {
        renderNames()
        PresenceService.renamed(this)
    }

    private fun renderNames() {
        namesList.removeAllViews()
        val entries = names.all()
        if (entries.isEmpty()) {
            namesList.addView(TextView(this, null, 0, R.style.Help).apply { setText(R.string.names_empty) })
            return
        }
        val density = resources.displayMetrics.density
        for (entry in entries) {
            val block = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (12 * density).toInt(), 0, 0)
            }
            block.addView(TextView(this).apply {
                text = entry.name
                textSize = 16f
                setTextColor(themeColor(android.R.attr.textColorPrimary))
            })
            block.addView(TextView(this, null, 0, R.style.Help).apply {
                text = getString(R.string.names_detected_as, entry.detected)
            })
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(Button(this).apply {
                setText(R.string.names_edit)
                setOnClickListener { askForName(entry.key, entry.detected, entry.name) }
            })
            buttons.addView(Button(this).apply {
                setText(R.string.names_remove)
                setOnClickListener {
                    names.remove(entry.key)
                    onNamesChanged()
                }
            })
            block.addView(buttons)
            namesList.addView(block)
        }
    }

    private fun themeColor(attr: Int): android.content.res.ColorStateList? {
        val a = obtainStyledAttributes(intArrayOf(attr))
        return try {
            a.getColorStateList(0)
        } finally {
            a.recycle()
        }
    }

    /** Kizzy's own presence modes, minus the Custom RPC Kizzy+ itself starts in Kizzy mode. */
    private fun otherKizzyModes(): Set<KizzyServices.Mode>? {
        val running = KizzyServices.running() ?: return null
        return if (settings.backend == Settings.BACKEND_KIZZY && PresenceService.running) {
            running - KizzyServices.Mode.CUSTOM
        } else {
            running
        }
    }

    private fun checkKizzy() {
        io.execute {
            val modes = otherKizzyModes().orEmpty()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (modes.isEmpty()) {
                    kizzyWarning.visibility = View.GONE
                } else {
                    kizzyWarning.text = getString(R.string.kizzy_running_warning, modes.joinToString(" and ") { it.label })
                    kizzyWarning.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun clearDiscordStatus() {
        clearStatus.isEnabled = false
        PresenceService.clear(this)
        io.execute {
            val modes = otherKizzyModes().orEmpty()
            if (modes.isNotEmpty()) {
                // Kizzy's shortcut is an activity, so it starts from the UI thread; Custom RPC needs root.
                val viaShortcut = modes - KizzyServices.Mode.CUSTOM
                if (viaShortcut.isNotEmpty()) runOnUiThread { KizzyServices.stop(this, viaShortcut) }
                if (KizzyServices.Mode.CUSTOM in modes) KizzyServices.stop(this, setOf(KizzyServices.Mode.CUSTOM))
                // Kizzy's services take a moment to stop after its shortcut runs.
                Thread.sleep(2_000)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                clearStatus.isEnabled = true
                Toast.makeText(this, R.string.clear_status_done, Toast.LENGTH_LONG).show()
                checkKizzy()
            }
        }
    }

    private fun refreshAccount() {
        val kizzyMode = settings.backend == Settings.BACKEND_KIZZY
        accountButtons.visibility = if (kizzyMode) View.GONE else View.VISIBLE
        if (accountBusy) return
        accountStatus.text = when {
            kizzyMode -> getString(R.string.account_kizzy_mode)
            TokenStore.hasToken(this) -> getString(R.string.account_signed_in, TokenStore.displayName(this) ?: "?")
            else -> getString(R.string.account_signed_out)
        }
        signOut.isEnabled = TokenStore.hasToken(this)
    }

    private fun setAccountBusy(busy: Boolean) {
        accountBusy = busy
        listOf(signInWeb, signInKizzy, signInPaste, signOut).forEach { it.isEnabled = !busy }
        if (!busy) signOut.isEnabled = TokenStore.hasToken(this)
    }

    private fun verifyAndSave(token: String) {
        val trimmed = token.trim().removeSurrounding("\"")
        if (trimmed.isEmpty()) return
        setAccountBusy(true)
        accountStatus.text = getString(R.string.account_checking)
        io.execute {
            val result = DiscordApi.currentUser(trimmed)
            if (result is DiscordApi.UserResult.Ok) TokenStore.save(this, trimmed, result.displayName)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setAccountBusy(false)
                refreshAccount()
                when (result) {
                    is DiscordApi.UserResult.Ok -> PresenceService.refresh(this)
                    DiscordApi.UserResult.Rejected -> accountStatus.text = getString(R.string.token_rejected)
                    is DiscordApi.UserResult.Failed -> accountStatus.text = getString(R.string.token_network, result.message)
                }
            }
        }
    }

    private fun importFromKizzy() {
        setAccountBusy(true)
        accountStatus.text = getString(R.string.account_checking)
        io.execute {
            val token = KizzyImport.readToken()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setAccountBusy(false)
                if (token == null) {
                    refreshAccount()
                    accountStatus.text = getString(R.string.kizzy_no_token)
                } else {
                    verifyAndSave(token)
                }
            }
        }
    }

    private fun askForToken() {
        val field = EditText(this).apply {
            hint = getString(R.string.paste_hint)
            // Single-line first: setting it afterwards swaps out the password masking.
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val frame = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.paste_title)
            .setView(frame)
            .setPositiveButton(R.string.paste_save) { _, _ -> verifyAndSave(field.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        field.requestFocus()
    }

    private fun updateBackendNote() {
        if (settings.backend != Settings.BACKEND_KIZZY) {
            backendNote.text = getString(R.string.backend_builtin_note)
            return
        }
        val base = getString(R.string.backend_kizzy_note)
        backendNote.text = base
        io.execute {
            val extra = when {
                !KizzyImport.isInstalled() -> "Kizzy isn't installed on this device."
                KizzyImport.readToken() == null -> "Kizzy is installed but not signed in. Sign in inside Kizzy first."
                else -> "Kizzy is installed and signed in."
            }
            runOnUiThread {
                if (!isDestroyed && settings.backend == Settings.BACKEND_KIZZY) backendNote.text = "$base\n\n$extra"
            }
        }
    }

    private fun runCheck() {
        debugRun.isEnabled = false
        debugOutput.visibility = View.VISIBLE
        debugOutput.text = getString(R.string.debug_running)
        io.execute {
            val text = try {
                Diagnostics.run(this)
            } catch (e: Exception) {
                "The check failed: $e"
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                report = text
                debugOutput.text = text
                debugRun.isEnabled = true
                debugCopy.isEnabled = true
            }
        }
    }

    private fun saveAdvanced() {
        val id = appId.text.toString().trim()
        if (id.isNotEmpty() && !Regex("\\d{17,20}").matches(id)) {
            appId.error = getString(R.string.app_id_invalid)
            appId.requestFocus()
            return
        }
        settings.applicationId = id.ifEmpty { Settings.DEFAULT_APPLICATION_ID }
        // Write back only the names that passed validation, so dropped entries are visible.
        settings.extraGameNative = Settings.parsePackages(extraGameNative.text.toString()).joinToString(", ")
        settings.extraEden = Settings.parsePackages(extraEden.text.toString()).joinToString(", ")
        extraGameNative.setText(settings.extraGameNative)
        extraEden.setText(settings.extraEden)
        Toast.makeText(this, R.string.advanced_saved, Toast.LENGTH_SHORT).show()
        PresenceService.refresh(this)
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun spinnerAdapter(labels: Int) =
        ArrayAdapter.createFromResource(this, labels, android.R.layout.simple_spinner_item).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun onSelected(block: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (!binding) block(position)
        }

        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    companion object {
        private const val REQUEST_LOGIN = 1
        private const val REQUEST_NOTIFICATIONS = 2
    }
}
