package com.christian.devialetremote

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var controller: DevialetController
    private lateinit var statusListener: DevialetStatusListener
    private lateinit var network: ExecutorService

    private lateinit var deviceCard: LinearLayout
    private lateinit var deviceDot: View
    private lateinit var txtDeviceName: TextView
    private lateinit var txtDeviceSub: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtVolumeDb: TextView
    private lateinit var seekVolume: SeekBar
    private lateinit var btnMute: Button
    private lateinit var btnPower: Button
    private lateinit var sourcesContainer: LinearLayout

    private var userIsDraggingSlider = false
    private var isMuted = false
    private var isPoweredOn = true

    // The amp currently selected for control, and everything this app has
    // heard broadcasting on the LAN recently (keyed by IP) - the latter is
    // what populates the "Choose Amplifier" picker so nobody has to type an
    // IP address. Both live on the UI thread only: statusListener's callback
    // is already wrapped in runOnUiThread (see below), so no locking needed.
    private var selectedIp: String = ""
    private var selectedName: String = ""
    private val discoveredAmps = LinkedHashMap<String, DiscoveredAmp>()

    // An amp not heard from in this long is treated as gone (dropped from the
    // picker list, device card flips to "Not responding"). Broadcasts land
    // about once a second, so this comfortably survives a couple of missed
    // packets without being so long that a genuinely offline amp lingers.
    private val ampStaleTimeoutMs = 8_000L

    // Redraws the device card's connected/not-responding dot periodically,
    // since that state can go stale even when no new packet arrives to
    // trigger a redraw the normal way (e.g. the selected amp just went dark).
    private val cardRefreshHandler = Handler(Looper.getMainLooper())
    private val cardRefreshRunnable = object : Runnable {
        override fun run() {
            updateDeviceCard()
            cardRefreshHandler.postDelayed(this, 2_000L)
        }
    }

    // Re-renders the amplifier picker dialog while it's open, so amps that
    // appear (or go stale) while the user is looking at the list show up
    // without them having to close and reopen it.
    private val pickerRefreshHandler = Handler(Looper.getMainLooper())
    private var pickerRefreshRunnable: Runnable? = null

    // Press-and-hold repeat for VOL -/+. Steps are 0.5dB (one slider progress
    // unit), so a 100ms interval works out to 5dB/sec while held.
    private val volumeRepeatHandler = Handler(Looper.getMainLooper())
    private var volumeRepeatRunnable: Runnable? = null
    private val volumeRepeatInitialDelayMs = 300L
    private val volumeRepeatIntervalMs = 100L

    // While the user is actively stepping volume via VOL -/+, the amp's own
    // status broadcasts (~1x/sec, but can land mid-hold) are ignored for a
    // short window after each step - otherwise an in-flight broadcast reporting
    // the pre-step volume overwrites the slider and makes it jump/jerk while
    // held. Window is the repeat interval plus a small buffer so it comfortably
    // covers the gap between repeat steps.
    private val volumeButtonDebounceMs = volumeRepeatIntervalMs + 300L
    private var lastVolumeButtonStepAtMs = 0L

    // Same race, different trigger: releasing the slider sends the new volume,
    // but an in-flight status broadcast reporting the pre-release volume can
    // land right after and snap the slider back before the next broadcast
    // (with the real new value) snaps it forward again - i.e. the jump/jerk
    // the VOL -/+ debounce above already prevents. It's the same single
    // setVolumeDb round-trip either way, so reuse that debounce window.
    private var lastVolumeSliderReleaseAtMs = 0L


    // Slider maps progress 0..90 to dB range -60..-15, in 0.5dB steps
    private fun progressToDb(progress: Int): Double = (progress * 0.5) - 60.0
    private fun dbToProgress(db: Double): Int = ((db + 60.0) * 2).toInt().coerceIn(0, 90)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // Suppresses "Button does not override performClick" - it's a stock Button,
    // not a custom View subclass, and it already inherits a working
    // performClick() from View. We do call it explicitly below (on ACTION_UP),
    // which satisfies the actual accessibility concern; subclassing Button just
    // to silence this specific lint check isn't worth the complexity.
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("devialet_remote", MODE_PRIVATE)
        network = Executors.newSingleThreadExecutor()

        deviceCard = findViewById(R.id.deviceCard)
        deviceDot = findViewById(R.id.deviceDot)
        txtDeviceName = findViewById(R.id.txtDeviceName)
        txtDeviceSub = findViewById(R.id.txtDeviceSub)
        txtStatus = findViewById(R.id.txtStatus)
        txtVolumeDb = findViewById(R.id.txtVolumeDb)
        seekVolume = findViewById(R.id.seekVolume)
        btnMute = findViewById(R.id.btnMute)
        btnPower = findViewById(R.id.btnPower)
        sourcesContainer = findViewById(R.id.sourcesContainer)

        selectedIp = prefs.getString("amp_ip", "") ?: ""
        selectedName = prefs.getString("amp_name", "") ?: ""
        controller = DevialetController(selectedIp)
        updateDeviceCard()

        deviceCard.setOnClickListener { showAmpPickerDialog() }

        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val db = progressToDb(progress)
                txtVolumeDb.text = getString(R.string.volume_db_format, db)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { userIsDraggingSlider = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userIsDraggingSlider = false
                lastVolumeSliderReleaseAtMs = SystemClock.elapsedRealtime()
                val db = progressToDb(seekBar?.progress ?: 0)
                sendVolume(db)
            }
        })

        val btnVolUp = findViewById<Button>(R.id.btnVolUp)
        val btnVolDown = findViewById<Button>(R.id.btnVolDown)

        btnVolUp.setOnTouchListener { v, event ->
            handleVolumeButtonTouch(event, +1)
            if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            true
        }
        btnVolDown.setOnTouchListener { v, event ->
            handleVolumeButtonTouch(event, -1)
            if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            true
        }

        btnMute.setOnClickListener {
            isMuted = !isMuted
            updateMuteButton()
            requireIp {
                network.submit { runCatching { controller.setMute(isMuted) } }
            }
        }

        btnPower.setOnClickListener {
            isPoweredOn = !isPoweredOn
            updatePowerButton()
            requireIp {
                network.submit { runCatching { controller.setPower(isPoweredOn) } }
            }
        }

        updateMuteButton()
        updatePowerButton()

        // Listens for every amp's status broadcasts (not just the selected
        // one - see applyStatus) to show live volume/mute/power/source, to
        // populate the source buttons, and to build the amplifier picker
        // list. Purely informational/discovery - control buttons above work
        // even if this never receives anything.
        statusListener = DevialetStatusListener { status, senderIp ->
            runOnUiThread { applyStatus(status, senderIp) }
        }
    }

    private fun requireIp(action: () -> Unit) {
        if (controller.deviceIp.isBlank()) {
            Toast.makeText(this, R.string.toast_select_amp_first, Toast.LENGTH_SHORT).show()
        } else {
            action()
        }
    }

    private fun sendVolume(db: Double) {
        requireIp {
            network.submit { runCatching { controller.setVolumeDb(db) } }
        }
    }

    /**
     * Handles VOL -/+ touch events: a quick tap does a single 0.5dB step (fires
     * on ACTION_DOWN so it's instant), and holding the button down auto-repeats
     * steps at [volumeRepeatIntervalMs] after an initial [volumeRepeatInitialDelayMs]
     * delay, until the finger lifts or leaves the button. (performClick() is
     * called separately, from the setOnTouchListener lambda itself.)
     */
    private fun handleVolumeButtonTouch(event: MotionEvent, direction: Int) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startVolumeRepeat(direction)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopVolumeRepeat()
        }
    }

    private fun startVolumeRepeat(direction: Int) {
        stopVolumeRepeat()
        stepVolume(direction) // immediate response to the initial press
        val runnable = object : Runnable {
            override fun run() {
                stepVolume(direction)
                volumeRepeatHandler.postDelayed(this, volumeRepeatIntervalMs)
            }
        }
        volumeRepeatRunnable = runnable
        volumeRepeatHandler.postDelayed(runnable, volumeRepeatInitialDelayMs)
    }

    private fun stopVolumeRepeat() {
        volumeRepeatRunnable?.let { volumeRepeatHandler.removeCallbacks(it) }
        volumeRepeatRunnable = null
    }

    private fun stepVolume(direction: Int) {
        lastVolumeButtonStepAtMs = SystemClock.elapsedRealtime()
        seekVolume.progress = (seekVolume.progress + direction).coerceIn(0, seekVolume.max)
        sendVolume(progressToDb(seekVolume.progress))
    }

    private fun updateMuteButton() {
        btnMute.setText(if (isMuted) R.string.btn_unmute else R.string.btn_mute)
    }

    private fun updatePowerButton() {
        btnPower.setText(if (isPoweredOn) R.string.btn_power_off else R.string.btn_power_on)
    }

    // ---- Amplifier discovery & selection ----

    private fun isAmpRecentlyHeard(ip: String): Boolean {
        val amp = discoveredAmps[ip] ?: return false
        return SystemClock.elapsedRealtime() - amp.lastSeenAtMs < ampStaleTimeoutMs
    }

    private fun updateDeviceCard() {
        if (selectedIp.isBlank()) {
            txtDeviceName.text = getString(R.string.device_none_selected)
            txtDeviceSub.text = getString(R.string.device_tap_to_choose)
            deviceDot.setBackgroundResource(R.drawable.dot_disconnected)
            return
        }
        txtDeviceName.text = selectedName.ifBlank { selectedIp }
        val connected = isAmpRecentlyHeard(selectedIp)
        txtDeviceSub.text = getString(
            R.string.device_sub_format,
            selectedIp,
            getString(if (connected) R.string.device_connected else R.string.device_not_responding)
        )
        deviceDot.setBackgroundResource(if (connected) R.drawable.dot_connected else R.drawable.dot_disconnected)
    }

    private fun selectAmp(ip: String, name: String) {
        selectedIp = ip
        selectedName = name.ifBlank { discoveredAmps[ip]?.deviceName ?: "" }
        prefs.edit {
            putString("amp_ip", selectedIp)
            putString("amp_name", selectedName)
        }
        controller.deviceIp = selectedIp
        updateDeviceCard()
        Toast.makeText(this, getString(R.string.toast_saved_talking, selectedIp), Toast.LENGTH_SHORT).show()
    }

    /**
     * Shows every amp heard from in the last [ampStaleTimeoutMs], refreshing
     * live while open, plus a manual-IP fallback for the rare case discovery
     * doesn't work (different subnet/VLAN, amp hasn't broadcast yet, etc.).
     */
    private fun showAmpPickerDialog() {
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val scroll = ScrollView(this).apply { addView(listContainer) }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_choose_amp_title)
            .setView(scroll)
            .setNeutralButton(R.string.dialog_enter_ip_manually) { _, _ -> showManualIpDialog() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()

        fun render() {
            listContainer.removeAllViews()
            val fresh = discoveredAmps.values
                .filter { isAmpRecentlyHeard(it.ipAddress) }
                .sortedBy { it.deviceName.ifBlank { it.ipAddress } }

            if (fresh.isEmpty()) {
                listContainer.addView(TextView(this).apply {
                    text = getString(R.string.dialog_searching)
                    setPadding(dp(12), dp(20), dp(12), dp(20))
                })
            } else {
                for (amp in fresh) {
                    listContainer.addView(buildAmpRow(amp, dialog))
                }
            }
        }

        render()
        val refresh = object : Runnable {
            override fun run() {
                render()
                pickerRefreshHandler.postDelayed(this, 1_000L)
            }
        }
        pickerRefreshRunnable = refresh
        pickerRefreshHandler.postDelayed(refresh, 1_000L)

        dialog.setOnDismissListener {
            pickerRefreshRunnable?.let { pickerRefreshHandler.removeCallbacks(it) }
            pickerRefreshRunnable = null
        }

        dialog.show()
    }

    private fun buildAmpRow(amp: DiscoveredAmp, dialog: AlertDialog): View {
        return TextView(this).apply {
            text = if (amp.ipAddress == selectedIp) {
                getString(R.string.amp_row_selected_format, amp.deviceName, amp.ipAddress)
            } else {
                getString(R.string.amp_row_format, amp.deviceName, amp.ipAddress)
            }
            textSize = 16f
            setPadding(dp(12), dp(14), dp(12), dp(14))
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener {
                selectAmp(amp.ipAddress, amp.deviceName)
                dialog.dismiss()
            }
        }
    }

    private fun showManualIpDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.hint_amp_ip)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(selectedIp)
        }
        val padded = FrameLayout(this).apply {
            val horizontalPad = dp(20)
            setPadding(horizontalPad, dp(8), horizontalPad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_enter_ip_manually)
            .setView(padded)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) selectAmp(ip, "")
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // ---- Status broadcasts ----

    private fun applyStatus(status: DevialetStatus, senderIp: String) {
        // Every amp on the LAN broadcasts, regardless of what's selected -
        // record it so it shows up in the picker, then refresh the device
        // card if it's the one currently selected.
        discoveredAmps[senderIp] = DiscoveredAmp(senderIp, status.deviceName, SystemClock.elapsedRealtime())
        if (senderIp == selectedIp) updateDeviceCard()

        if (selectedIp.isBlank() || senderIp != selectedIp) return // ignore other amps' live status

        txtStatus.text = getString(
            R.string.status_format,
            status.deviceName,
            senderIp,
            getString(if (status.powerOn) R.string.power_on else R.string.power_off),
            status.muted,
            status.currentSourceName
        )

        isMuted = status.muted
        isPoweredOn = status.powerOn
        updateMuteButton()
        updatePowerButton()

        val recentlyPressedVolumeButton =
            SystemClock.elapsedRealtime() - lastVolumeButtonStepAtMs < volumeButtonDebounceMs
        val recentlyReleasedSlider =
            SystemClock.elapsedRealtime() - lastVolumeSliderReleaseAtMs < volumeButtonDebounceMs
        if (!userIsDraggingSlider && !recentlyPressedVolumeButton && !recentlyReleasedSlider) {
            seekVolume.progress = dbToProgress(status.volumeDb)
            txtVolumeDb.text = getString(R.string.volume_db_format, status.volumeDb)
        }

        rebuildSourceButtons(status)
    }

    private fun rebuildSourceButtons(status: DevialetStatus) {
        // Tag includes both name AND selection state, so a source change
        // (not just the set of sources changing) triggers a rebuild and the
        // "(ACTIVE)" label stays in sync. Comparing names alone meant this
        // always short-circuited after the first draw, since the list of
        // available sources doesn't change when you just switch inputs.
        val tagKey = status.enabledSources.map { it.name to it.isSelected }
        if (sourcesContainer.tag == tagKey) return // avoid rebuilding every second
        sourcesContainer.removeAllViews()
        sourcesContainer.tag = tagKey
        for (source in status.enabledSources) {
            val button = Button(this).apply {
                text = if (source.isSelected) {
                    getString(R.string.source_active_format, source.name)
                } else {
                    source.name
                }
                setOnClickListener {
                    requireIp {
                        network.submit { runCatching { controller.selectSource(source.index) } }
                    }
                }
            }
            sourcesContainer.addView(button)
        }
    }

    override fun onResume() {
        super.onResume()
        statusListener.start()
        cardRefreshHandler.postDelayed(cardRefreshRunnable, 2_000L)
    }

    override fun onPause() {
        super.onPause()
        statusListener.stop()
        stopVolumeRepeat() // don't keep firing volume changes while backgrounded
        cardRefreshHandler.removeCallbacks(cardRefreshRunnable)
        pickerRefreshRunnable?.let { pickerRefreshHandler.removeCallbacks(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        network.shutdownNow()
    }
}
