package com.christian.devialetremote

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.widget.ImageViewCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var controller: DevialetController
    private lateinit var statusListener: DevialetStatusListener
    private lateinit var modelNameResolver: AmpModelNameResolver
    private lateinit var network: ExecutorService

    private lateinit var deviceCard: LinearLayout
    private lateinit var deviceDot: View
    private lateinit var txtDeviceName: TextView
    private lateinit var txtDeviceSub: TextView

    // Segmented control + the two tab content views it switches between.
    private lateinit var segControl: TextView
    private lateinit var segSound: TextView
    private lateinit var viewControl: LinearLayout
    private lateinit var viewSound: LinearLayout

    private lateinit var volumeDial: VolumeDialView
    private lateinit var dialWrap: LinearLayout
    private lateinit var txtVolumeDb: TextView
    private lateinit var txtDialUnit: TextView
    private lateinit var txtDialSource: TextView
    private lateinit var btnVolDown: View
    private lateinit var btnVolUp: View
    private lateinit var actionRow: LinearLayout
    private lateinit var txtControlFooterNote: TextView

    private lateinit var samSwitch: SwitchCompat
    private lateinit var txtSamState: TextView
    private lateinit var samSliderCard: LinearLayout
    private lateinit var seekSamLevel: SeekBar
    private lateinit var txtSamLevelValue: TextView

    private lateinit var nightSwitch: SwitchCompat
    private lateinit var txtNightState: TextView

    private lateinit var seekBass: SeekBar
    private lateinit var txtBassValue: TextView
    private lateinit var seekTreble: SeekBar
    private lateinit var txtTrebleValue: TextView

    private lateinit var soundControls: LinearLayout
    private lateinit var soundEmptyState: LinearLayout
    private lateinit var txtSoundFooterNote: TextView

    private lateinit var btnMute: LinearLayout
    private lateinit var imgMuteIcon: ImageView
    private lateinit var txtMuteLabel: TextView
    private lateinit var btnPower: LinearLayout
    private lateinit var imgPowerIcon: ImageView
    private lateinit var txtPowerLabel: TextView

    private lateinit var sourceTrigger: LinearLayout
    private lateinit var txtTriggerIcon: TextView
    private lateinit var txtTriggerName: TextView

    // The source picker's own Dialog + its rows container - both null
    // whenever the sheet is closed. Nullable (rather than lateinit) on
    // purpose: applyStatus() checks this every ~second to decide whether
    // there's a currently-open sheet worth live-updating.
    private var sourceSheetDialog: BottomSheetDialog? = null
    private var sourcesContainer: LinearLayout? = null

    // The amplifier picker's own Dialog - null whenever the sheet is closed.
    // Unlike the source sheet, its rows are always fully rebuilt from
    // scratch on each render() (see showAmpSheet), so there's no equivalent
    // of sourcesContainer to cache here.
    private var ampSheetDialog: BottomSheetDialog? = null

    // Most recent status broadcast for the selected amp, kept so the source
    // sheet can be populated immediately on open rather than waiting for the
    // next ~1/sec broadcast to arrive.
    private var latestStatus: DevialetStatus? = null

    private var userIsAdjustingVolume = false
    private var isMuted = false
    private var isPoweredOn = true

    // SAM on/off and Night Mode on/off, tracked independently of the
    // switches themselves: while no amplifier is selected both switches are
    // forced visually off (see updateConnectionState), which would otherwise
    // clobber the user's actual preference. These are what gets restored to
    // the switches once an amplifier is selected again.
    private var samOn = true
    private var nightOn = false

    // Set while updateConnectionState() is programmatically flipping
    // samSwitch/nightSwitch, so their OnCheckedChangeListener can tell a
    // real user toggle apart from one it triggered itself and skip updating
    // samOn/nightOn for the latter.
    private var suppressToggleCallbacks = false

    // The amp currently selected for control, and everything this app has
    // heard broadcasting on the LAN recently (keyed by IP) - the latter is
    // what populates the "Choose Amplifier" picker so nobody has to type an
    // IP address. Both live on the UI thread only: statusListener's callback
    // is already wrapped in runOnUiThread (see below), so no locking needed.
    private var selectedIp: String = ""
    private var selectedName: String = ""
    private val discoveredAmps = LinkedHashMap<String, DiscoveredAmp>()

    // True once an amplifier has actually been chosen (including explicitly
    // choosing "None" beforehand, which clears selectedIp again) - drives
    // whether the dial, mute/power, source trigger and Sound tab controls
    // are interactive or shown in their dimmed "nothing to control" state.
    // Deliberately independent of isAmpRecentlyHeard: a momentary missed
    // broadcast shouldn't gray out the whole UI, only "None" should.
    private val hasSelectedAmp: Boolean
        get() = selectedIp.isNotBlank()

    // Opacity applied to dialWrap/actionRow/soundControls (as a whole, not
    // per-child) when hasSelectedAmp is false - matches the mockup's
    // .disabled-overlay{opacity:0.4}.
    private val disabledAlpha = 0.4f

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

    // Press-and-hold repeat for VOL -/+. Steps are 0.5dB (one progress unit),
    // so a 100ms interval works out to 5dB/sec while held.
    private val volumeRepeatHandler = Handler(Looper.getMainLooper())
    private var volumeRepeatRunnable: Runnable? = null
    private val volumeRepeatInitialDelayMs = 300L
    private val volumeRepeatIntervalMs = 100L

    // While the user is actively stepping volume via VOL -/+, the amp's own
    // status broadcasts (~1x/sec, but can land mid-hold) are ignored for a
    // short window after each step - otherwise an in-flight broadcast reporting
    // the pre-step volume overwrites the dial and makes it jump/jerk while
    // held. Window is the repeat interval plus a small buffer so it comfortably
    // covers the gap between repeat steps.
    private val volumeButtonDebounceMs = volumeRepeatIntervalMs + 300L
    private var lastVolumeButtonStepAtMs = 0L

    // Same race as the button debounce above, but for dragging the volume
    // dial directly: releasing sends the new volume, but an in-flight status
    // broadcast reporting the pre-release volume can land right after and
    // snap the dial back before the next broadcast (with the real value)
    // snaps it forward again. Same amp round-trip either way, so this reuses
    // volumeButtonDebounceMs rather than inventing a second constant.
    private var lastVolumeSliderReleaseAtMs = 0L

    // SAM level (0-100%), Bass and Treble (-18..+18dB): UI-only for now, same
    // as the SAM on/off and Night Mode switches - see the TODO stubs in
    // DevialetController for where each plugs in once the UDP bytes are
    // known. Keeping the local state here (rather than only in the widgets)
    // means it survives a tab switch even though viewSound gets hidden, not
    // torn down.
    private var samLevel = 70
    private var bassDb = 0
    private var trebleDb = 0

    // Bass/Treble SeekBars use a plain 0..toneRangeSteps progress (XML "min"
    // isn't reliable pre-API26, and minSdk here is 24) representing
    // toneMinDb..toneMaxDb - toneMinDb is the zero point.
    private val toneMinDb = -18
    private val toneMaxDb = 18
    private val toneRangeSteps = toneMaxDb - toneMinDb // 36

    private fun toneDbToProgress(db: Int): Int = (db - toneMinDb).coerceIn(0, toneRangeSteps)
    private fun toneProgressToDb(progress: Int): Int = (progress + toneMinDb).coerceIn(toneMinDb, toneMaxDb)

    // Volume steps 0..90 map to dB range -60..-15 in 0.5dB increments. This
    // range (not the full -60..0 the amp supports) matches setVolumeDb's
    // default safety clamp in DevialetController - kept identical to the
    // pre-redesign behavior, only the visuals changed here.
    private val minDb = -60.0
    private val maxDb = -15.0
    private val maxSteps = ((maxDb - minDb) * 2).toInt() // 90
    private var volumeStep = dbToStep(-20.0)

    private fun stepToDb(step: Int): Double = minDb + (step * 0.5)
    private fun dbToStep(db: Double): Int = (((db - minDb) * 2).toInt()).coerceIn(0, maxSteps)

    // A small rotating set of glyphs for the source list, since the amp's
    // source list is arbitrary text from its own config, not a fixed enum -
    // purely decorative, mirrors the varied icons in the mockup.
    private val sourceIconGlyphs = listOf("◉", "◫", "◍", "◈", "◐", "◇")

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // Suppresses "View does not override performClick" - we call
    // performClick() explicitly on ACTION_UP below, which satisfies the
    // actual accessibility concern.
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

        segControl = findViewById(R.id.segControl)
        segSound = findViewById(R.id.segSound)
        viewControl = findViewById(R.id.viewControl)
        viewSound = findViewById(R.id.viewSound)

        volumeDial = findViewById(R.id.volumeDial)
        dialWrap = findViewById(R.id.dialWrap)
        txtVolumeDb = findViewById(R.id.txtVolumeDb)
        txtDialUnit = findViewById(R.id.txtDialUnit)
        txtDialSource = findViewById(R.id.txtDialSource)
        btnVolDown = findViewById(R.id.btnVolDown)
        btnVolUp = findViewById(R.id.btnVolUp)
        actionRow = findViewById(R.id.actionRow)
        txtControlFooterNote = findViewById(R.id.txtControlFooterNote)

        samSwitch = findViewById(R.id.samSwitch)
        txtSamState = findViewById(R.id.txtSamState)
        samSliderCard = findViewById(R.id.samSliderCard)
        seekSamLevel = findViewById(R.id.seekSamLevel)
        txtSamLevelValue = findViewById(R.id.txtSamLevelValue)

        nightSwitch = findViewById(R.id.nightSwitch)
        txtNightState = findViewById(R.id.txtNightState)

        seekBass = findViewById(R.id.seekBass)
        txtBassValue = findViewById(R.id.txtBassValue)
        seekTreble = findViewById(R.id.seekTreble)
        txtTrebleValue = findViewById(R.id.txtTrebleValue)

        soundControls = findViewById(R.id.soundControls)
        soundEmptyState = findViewById(R.id.soundEmptyState)
        txtSoundFooterNote = findViewById(R.id.txtSoundFooterNote)

        btnMute = findViewById(R.id.btnMute)
        imgMuteIcon = findViewById(R.id.imgMuteIcon)
        txtMuteLabel = findViewById(R.id.txtMuteLabel)
        btnPower = findViewById(R.id.btnPower)
        imgPowerIcon = findViewById(R.id.imgPowerIcon)
        txtPowerLabel = findViewById(R.id.txtPowerLabel)

        sourceTrigger = findViewById(R.id.sourceTrigger)
        txtTriggerIcon = findViewById(R.id.txtTriggerIcon)
        txtTriggerName = findViewById(R.id.txtTriggerName)

        selectedIp = prefs.getString("amp_ip", "") ?: ""
        selectedName = prefs.getString("amp_name", "") ?: ""
        controller = DevialetController(selectedIp)
        updateDeviceCard()

        deviceCard.setOnClickListener { showAmpSheet() }

        segControl.setOnClickListener { showTab(Tab.CONTROL) }
        segSound.setOnClickListener { showTab(Tab.SOUND) }

        renderVolume()

        btnVolUp.setOnTouchListener { v, event ->
            if (!v.isEnabled) return@setOnTouchListener false
            handleVolumeButtonTouch(event, +1)
            if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            true
        }
        btnVolDown.setOnTouchListener { v, event ->
            if (!v.isEnabled) return@setOnTouchListener false
            handleVolumeButtonTouch(event, -1)
            if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            true
        }

        volumeDial.onDragStart = { userIsAdjustingVolume = true }
        volumeDial.onDrag = { fraction ->
            volumeStep = (fraction * maxSteps).toInt().coerceIn(0, maxSteps)
            renderVolume() // live feedback only, nothing sent to the amp yet
        }
        volumeDial.onDragEnd = { fraction ->
            volumeStep = (fraction * maxSteps).toInt().coerceIn(0, maxSteps)
            renderVolume()
            lastVolumeSliderReleaseAtMs = SystemClock.elapsedRealtime()
            sendVolume(stepToDb(volumeStep))
            userIsAdjustingVolume = false
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

        // SAM / Night Mode / SAM level / Bass / Treble: UI-only for now. The
        // UDP command bytes for these haven't been reverse-engineered yet
        // (unlike volume/mute/power/source above), so none of this talks to
        // the amp at all - no risk of sending a command that does the wrong
        // thing. See the TODO stubs in DevialetController for where each
        // plugs in once the bytes are known - it's the same shape as
        // setMute/setPower above in every case, just a `network.submit { ... }`
        // call added to the listener already sitting here.
        samSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressToggleCallbacks) return@setOnCheckedChangeListener
            samOn = isChecked
            updateSamStateLabel(isChecked)
            updateSamSliderEnabled(isChecked)
        }
        nightSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressToggleCallbacks) return@setOnCheckedChangeListener
            nightOn = isChecked
            txtNightState.text = getString(if (isChecked) R.string.state_on else R.string.state_off)
        }

        txtSamLevelValue.text = getString(R.string.sam_level_value_format, samLevel)
        seekSamLevel.progress = samLevel
        seekSamLevel.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                samLevel = progress
                txtSamLevelValue.text = getString(R.string.sam_level_value_format, samLevel)
                if (samSwitch.isChecked) updateSamStateLabel(true)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekBass.progress = toneDbToProgress(bassDb)
        renderToneValue(txtBassValue, bassDb)
        seekBass.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                bassDb = toneProgressToDb(progress)
                renderToneValue(txtBassValue, bassDb)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekTreble.progress = toneDbToProgress(trebleDb)
        renderToneValue(txtTrebleValue, trebleDb)
        seekTreble.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                trebleDb = toneProgressToDb(progress)
                renderToneValue(txtTrebleValue, trebleDb)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sourceTrigger.setOnClickListener { showSourceSheet() }

        updateMuteButton()
        updatePowerButton()
        updateConnectionState()

        // Listens for every amp's status broadcasts (not just the selected
        // one - see applyStatus) to show live volume/mute/power/source, to
        // populate the source list, and to build the amplifier picker list.
        // Purely informational/discovery - control buttons above work even
        // if this never receives anything.
        statusListener = DevialetStatusListener { status, senderIp ->
            runOnUiThread { applyStatus(status, senderIp) }
        }

        // Cosmetic only (see AmpModelNameResolver doc) - a no-op below API 36.
        // Matches are confirmed against discoveredAmps before being trusted,
        // since _spotify-connect._tcp isn't Devialet-specific on its own.
        modelNameResolver = AmpModelNameResolver(this) { ip, modelName ->
            discoveredAmps[ip]?.let { existing ->
                discoveredAmps[ip] = existing.copy(modelName = modelName)
                if (ip == selectedIp) updateDeviceCard()
            }
        }
    }

    // ---- Tabs ----

    private enum class Tab { CONTROL, SOUND }

    private fun showTab(tab: Tab) {
        val showControl = tab == Tab.CONTROL
        viewControl.visibility = if (showControl) View.VISIBLE else View.GONE
        viewSound.visibility = if (showControl) View.GONE else View.VISIBLE

        segControl.background = if (showControl) ContextCompat.getDrawable(this, R.drawable.shape_segment_selected) else null
        segSound.background = if (showControl) null else ContextCompat.getDrawable(this, R.drawable.shape_segment_selected)

        val activeColor = ContextCompat.getColor(this, R.color.color_copper_bright)
        val inactiveColor = ContextCompat.getColor(this, R.color.color_text_dim)
        segControl.setTextColor(if (showControl) activeColor else inactiveColor)
        segSound.setTextColor(if (showControl) inactiveColor else activeColor)
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

    private fun renderVolume() {
        if (!hasSelectedAmp) {
            txtVolumeDb.text = getString(R.string.dial_no_source)
            volumeDial.setProgress(0f)
            return
        }
        val db = stepToDb(volumeStep)
        txtVolumeDb.text = getString(R.string.volume_db_format, db)
        volumeDial.setProgress(volumeStep / maxSteps.toFloat())
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
        userIsAdjustingVolume = true
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
        userIsAdjustingVolume = false
    }

    private fun stepVolume(direction: Int) {
        lastVolumeButtonStepAtMs = SystemClock.elapsedRealtime()
        volumeStep = (volumeStep + direction).coerceIn(0, maxSteps)
        renderVolume()
        sendVolume(stepToDb(volumeStep))
    }

    private fun updateSamStateLabel(isOn: Boolean) {
        txtSamState.text = if (isOn) {
            getString(R.string.sam_state_on_format, samLevel)
        } else {
            getString(R.string.sam_state_off_format)
        }
    }

    private fun updateSamSliderEnabled(isOn: Boolean) {
        seekSamLevel.isEnabled = isOn
        samSliderCard.alpha = if (isOn) 1f else disabledAlpha
    }

    private fun renderToneValue(target: TextView, db: Int) {
        val sign = if (db > 0) "+" else ""
        target.text = getString(R.string.tone_value_format, sign, db)
    }

    private fun updateMuteButton() {
        txtMuteLabel.text = getString(R.string.btn_mute)
        btnMute.setBackgroundResource(if (isMuted) R.drawable.bg_card_active else R.drawable.bg_card)
        val tint = if (isMuted) {
            ContextCompat.getColor(this, R.color.color_copper_bright)
        } else {
            ContextCompat.getColor(this, R.color.color_text)
        }
        ImageViewCompat.setImageTintList(imgMuteIcon, ColorStateList.valueOf(tint))
        txtMuteLabel.setTextColor(tint)
    }

    private fun updatePowerButton() {
        txtPowerLabel.text = getString(if (isPoweredOn) R.string.btn_power_off else R.string.btn_power_on)
        btnPower.setBackgroundResource(if (isPoweredOn) R.drawable.bg_card else R.drawable.bg_card_danger_active)
        val tint = if (isPoweredOn) {
            ContextCompat.getColor(this, R.color.color_text)
        } else {
            ContextCompat.getColor(this, R.color.color_danger_soft)
        }
        ImageViewCompat.setImageTintList(imgPowerIcon, ColorStateList.valueOf(tint))
        txtPowerLabel.setTextColor(tint)
    }

    // ---- Connection state (no amplifier selected vs. one selected) ----

    /**
     * Dims [view] to [disabledAlpha] (or restores full opacity) and disables
     * every clickable/draggable descendant, matching the mockup's
     * .disabled-overlay{opacity:0.4; pointer-events:none}. Alpha is applied
     * only to [view] itself - Android composites a parent's alpha across its
     * whole subtree automatically, so setting it on every descendant too
     * would just multiply the dimming. isEnabled, on the other hand, does
     * NOT propagate to descendants on its own, so that part does need to be
     * recursive to actually block touches on things like SeekBars and
     * switches nested inside.
     */
    private fun setGroupEnabled(view: View, enabled: Boolean) {
        view.alpha = if (enabled) 1f else disabledAlpha
        setDescendantsEnabled(view, enabled)
    }

    private fun setDescendantsEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setDescendantsEnabled(view.getChildAt(i), enabled)
            }
        }
    }

    /**
     * The single place that reflects hasSelectedAmp across the UI: the
     * volume dial + mute/power (dialWrap/actionRow), the whole Sound tab
     * (soundControls, swapped for soundEmptyState), and SAM/Night Mode's
     * switches, which get forced off with a "no amplifier" label rather than
     * showing whatever was last toggled. samOn/nightOn hold the real
     * preference underneath so it's restored once an amplifier is selected
     * again - see the fields' own comments.
     */
    private fun updateConnectionState() {
        val connected = hasSelectedAmp

        setGroupEnabled(dialWrap, connected)
        setGroupEnabled(actionRow, connected)
        renderVolume()
        txtDialUnit.visibility = if (connected) View.VISIBLE else View.INVISIBLE

        // The source trigger stays clickable either way - opening it while
        // disconnected shows its own empty state (see showSourceSheet) -
        // only its opacity reflects the connection state.
        sourceTrigger.alpha = if (connected) 1f else disabledAlpha
        if (!connected) {
            txtDialSource.text = getString(R.string.no_source_label)
            txtTriggerName.text = getString(R.string.no_source_label)
            txtTriggerIcon.text = getString(R.string.source_icon_none)
        }

        txtControlFooterNote.text = getString(
            if (connected) R.string.footer_note else R.string.footer_note_disconnected
        )

        setGroupEnabled(soundControls, connected)
        soundEmptyState.visibility = if (connected) View.GONE else View.VISIBLE
        txtSoundFooterNote.visibility = if (connected) View.VISIBLE else View.GONE

        suppressToggleCallbacks = true
        samSwitch.isChecked = connected && samOn
        nightSwitch.isChecked = connected && nightOn
        suppressToggleCallbacks = false

        if (connected) {
            // Layered on top of the broad soundControls enable above: SAM
            // being off should still dim its level slider even though an
            // amp is selected, same as before this redesign.
            updateSamStateLabel(samOn)
            updateSamSliderEnabled(samOn)
            txtNightState.text = getString(if (nightOn) R.string.state_on else R.string.state_off)
        } else {
            txtSamState.text = getString(R.string.state_off_disconnected)
            samSliderCard.alpha = 1f // avoid compounding with soundControls' own dim above
            txtNightState.text = getString(R.string.state_off_disconnected)
        }
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
            deviceDot.setBackgroundResource(R.drawable.dot_none)
            return
        }
        txtDeviceName.text = discoveredAmps[selectedIp]?.modelName ?: selectedName.ifBlank { selectedIp }
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
        updateConnectionState()
        if (discoveredAmps[ip]?.modelName == null) {
            // Switching to an amp we haven't resolved a model name for yet -
            // don't make them wait out however much of the resolver's
            // current steady-state interval happens to be left.
            modelNameResolver.retryNow()
        }
        Toast.makeText(this, getString(R.string.toast_saved_talking, selectedIp), Toast.LENGTH_SHORT).show()
    }

    /**
     * Explicitly choosing "None" in the amp sheet - distinct from simply
     * never having chosen an amp yet, but ends up at the same selectedIp =
     * "" state either way (see hasSelectedAmp).
     */
    private fun clearAmpSelection() {
        selectedIp = ""
        selectedName = ""
        prefs.edit {
            putString("amp_ip", "")
            putString("amp_name", "")
        }
        controller.deviceIp = ""
        updateDeviceCard()
        updateConnectionState()
        Toast.makeText(this, R.string.toast_disconnected, Toast.LENGTH_SHORT).show()
    }

    /**
     * Shows a BottomSheetDialog (same pattern as the source picker) listing
     * "None" (to explicitly disconnect), every amp heard from in the last
     * [ampStaleTimeoutMs] - refreshed live while open - and a manual-IP
     * fallback that swaps in a second view within the same sheet rather than
     * opening a separate dialog, for the rare case discovery doesn't work
     * (different subnet/VLAN, amp hasn't broadcast yet, etc.).
     */
    @SuppressLint("InflateParams")
    private fun showAmpSheet() {
        val view = layoutInflater.inflate(R.layout.sheet_amp_picker, null)

        val listView = view.findViewById<LinearLayout>(R.id.ampListView)
        val manualView = view.findViewById<LinearLayout>(R.id.ampManualView)
        val listContainer = view.findViewById<LinearLayout>(R.id.ampListContainer)
        val noneRow = view.findViewById<LinearLayout>(R.id.ampNoneRow)
        val noneDot = view.findViewById<View>(R.id.ampNoneDot)
        val noneName = view.findViewById<TextView>(R.id.ampNoneName)
        val noneCheck = view.findViewById<TextView>(R.id.ampNoneCheck)
        val manualEntryRow = view.findViewById<LinearLayout>(R.id.ampManualEntryRow)
        val backBtn = view.findViewById<View>(R.id.btnAmpBack)
        val ipInput = view.findViewById<EditText>(R.id.inputManualIp)
        val connectBtn = view.findViewById<View>(R.id.btnConnectIp)

        val dialog = BottomSheetDialog(this, R.style.ThemeOverlay_DevialetRemote_BottomSheet)
        dialog.setContentView(view)

        fun showListView() {
            manualView.visibility = View.GONE
            listView.visibility = View.VISIBLE
        }

        fun showManualView() {
            listView.visibility = View.GONE
            manualView.visibility = View.VISIBLE
            ipInput.setText(selectedIp)
            ipInput.requestFocus()
            // Small delay so the newly-visible field has finished laying out
            // before we ask the system to show the keyboard on it.
            ipInput.postDelayed({ showKeyboard(ipInput) }, 250L)
        }

        fun render() {
            val noneSelected = !hasSelectedAmp
            noneDot.setBackgroundResource(if (noneSelected) R.drawable.dot_none_selected else R.drawable.dot_none)
            noneName.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (noneSelected) R.color.color_copper_bright else R.color.color_text_dim
                )
            )
            noneCheck.visibility = if (noneSelected) View.VISIBLE else View.INVISIBLE

            listContainer.removeAllViews()
            val fresh = discoveredAmps.values
                .filter { isAmpRecentlyHeard(it.ipAddress) }
                .sortedBy { (it.modelName ?: it.deviceName).ifBlank { it.ipAddress } }

            if (fresh.isEmpty()) {
                listContainer.addView(TextView(this).apply {
                    text = getString(R.string.dialog_searching)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.color_text_faint))
                    textSize = 12.5f
                    setPadding(dp(10), dp(16), dp(10), dp(16))
                })
            } else {
                for (amp in fresh) {
                    listContainer.addView(buildAmpRow(amp, dialog))
                }
            }
        }

        noneRow.setOnClickListener {
            clearAmpSelection()
            dialog.dismiss()
        }
        manualEntryRow.setOnClickListener { showManualView() }
        backBtn.setOnClickListener { showListView() }
        connectBtn.setOnClickListener {
            val ip = ipInput.text.toString().trim()
            if (ip.isNotEmpty()) {
                selectAmp(ip, "")
                dialog.dismiss()
            }
        }

        ampSheetDialog = dialog
        dialog.setOnDismissListener {
            ampSheetDialog = null
            pickerRefreshRunnable?.let { pickerRefreshHandler.removeCallbacks(it) }
            pickerRefreshRunnable = null
        }

        showListView()
        render()
        val refresh = object : Runnable {
            override fun run() {
                render()
                pickerRefreshHandler.postDelayed(this, 1_000L)
            }
        }
        pickerRefreshRunnable = refresh
        pickerRefreshHandler.postDelayed(refresh, 1_000L)

        dialog.show()
    }

    private fun buildAmpRow(amp: DiscoveredAmp, dialog: BottomSheetDialog): View {
        val selected = amp.ipAddress == selectedIp

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(13), dp(10), dp(13))
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.bg_source_row)
        }

        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(12) }
            setBackgroundResource(if (selected) R.drawable.dot_connected else R.drawable.dot_disconnected)
        }

        val info = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            orientation = LinearLayout.VERTICAL
        }
        val modelName = amp.modelName
        val name = TextView(this).apply {
            text = modelName ?: amp.deviceName.ifBlank { amp.ipAddress }
            textSize = 15f
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (selected) R.color.color_copper_bright else R.color.color_text
                )
            )
        }
        val sub = TextView(this).apply {
            // Once the model name is resolved and takes the primary line,
            // the friendly name becomes genuinely useful again as the
            // subtitle - it's what actually tells two amps of the same
            // model apart. Falls back to just the IP otherwise, same as before.
            text = if (modelName != null && amp.deviceName.isNotBlank()) {
                getString(R.string.device_sub_format, amp.deviceName, amp.ipAddress)
            } else {
                amp.ipAddress
            }
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.color_text_faint))
        }
        info.addView(name)
        info.addView(sub)

        val check = TextView(this).apply {
            text = getString(R.string.source_active_check)
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.color_copper_bright))
            visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }

        row.addView(dot)
        row.addView(info)
        row.addView(check)

        row.setOnClickListener {
            selectAmp(amp.ipAddress, amp.deviceName)
            dialog.dismiss()
        }

        return row
    }

    private fun showKeyboard(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    // ---- Source picker (bottom sheet) ----

    /**
     * A real BottomSheetDialog (native drag-to-dismiss, scrim, edge-to-edge
     * handling) rather than the old inline dropdown or SAM's side-drawer -
     * deliberately not a drawer, so opening/closing it can never be mistaken
     * for (or interfere with) the system back gesture the way the old
     * side-drawer could.
     */
    @SuppressLint("InflateParams")
    private fun showSourceSheet() {
        // Passing null here is intentional and safe: this view isn't being
        // attached to a parent at inflate time - it becomes the
        // BottomSheetDialog's content view via setContentView() below, which
        // is exactly the pattern the AndroidX dialog APIs expect. There's no
        // real parent to pass instead.
        val view = layoutInflater.inflate(R.layout.sheet_source_picker, null)
        val subtitle = view.findViewById<TextView>(R.id.txtSheetSubtitle)
        val container = view.findViewById<LinearLayout>(R.id.sourcesContainerSheet)
        val emptyState = view.findViewById<LinearLayout>(R.id.sourceEmptySheetState)

        subtitle.text = discoveredAmps[selectedIp]?.modelName ?: selectedName.ifBlank { selectedIp }

        val dialog = BottomSheetDialog(this, R.style.ThemeOverlay_DevialetRemote_BottomSheet)
        dialog.setContentView(view)
        dialog.setOnDismissListener {
            sourceSheetDialog = null
            sourcesContainer = null
        }

        sourceSheetDialog = dialog
        sourcesContainer = container

        if (!hasSelectedAmp) {
            // Nothing to list sources for - show the empty state instead.
            container.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
        } else {
            container.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
            // Force an immediate rebuild (rather than waiting for the next
            // status broadcast) using whatever we last heard, so the sheet
            // isn't empty for up to a second after opening.
            container.tag = null
            latestStatus?.let { rebuildSourceList(it) }
        }

        dialog.show()
    }

    private fun dismissSourceSheet() {
        sourceSheetDialog?.dismiss()
    }

    // ---- Status broadcasts ----

    private fun applyStatus(status: DevialetStatus, senderIp: String) {
        // Every amp on the LAN broadcasts, regardless of what's selected -
        // record it so it shows up in the picker, then refresh the device
        // card if it's the one currently selected.
        val previousModelName = discoveredAmps[senderIp]?.modelName
        discoveredAmps[senderIp] = DiscoveredAmp(senderIp, status.deviceName, SystemClock.elapsedRealtime(), previousModelName)
        if (senderIp == selectedIp) updateDeviceCard()

        if (selectedIp.isBlank() || senderIp != selectedIp) return // ignore other amps' live status

        latestStatus = status

        isMuted = status.muted
        isPoweredOn = status.powerOn
        updateMuteButton()
        updatePowerButton()

        val recentlyPressedVolumeButton =
            SystemClock.elapsedRealtime() - lastVolumeButtonStepAtMs < volumeButtonDebounceMs
        val recentlyReleasedSlider =
            SystemClock.elapsedRealtime() - lastVolumeSliderReleaseAtMs < volumeButtonDebounceMs
        if (!userIsAdjustingVolume && !recentlyPressedVolumeButton && !recentlyReleasedSlider) {
            volumeStep = dbToStep(status.volumeDb)
            renderVolume()
        }

        txtTriggerName.text = status.currentSourceName
        txtDialSource.text = status.currentSourceName
        val activeIndex = status.enabledSources.indexOfFirst { it.isSelected }
        if (activeIndex >= 0) {
            txtTriggerIcon.text = sourceIconGlyphs[activeIndex % sourceIconGlyphs.size]
        }

        rebuildSourceList(status)
    }

    private fun rebuildSourceList(status: DevialetStatus) {
        // Only rebuilds while the source sheet is actually open - the sheet
        // is created fresh each time it's shown (see showSourceSheet), so
        // there's nothing to update while it's closed.
        val container = sourcesContainer ?: return

        // Tag includes both name AND selection state, so a source change
        // (not just the set of sources changing) triggers a rebuild and the
        // active row stays in sync. Comparing names alone meant this always
        // short-circuited after the first draw, since the list of available
        // sources doesn't change when you just switch inputs.
        val tagKey = status.enabledSources.map { it.name to it.isSelected }
        if (container.tag == tagKey) return // avoid rebuilding every second
        container.removeAllViews()
        container.tag = tagKey

        val sources = status.enabledSources
        sources.forEachIndexed { i, source ->
            container.addView(buildSourceRow(source, sourceIconGlyphs[i % sourceIconGlyphs.size]))
            if (i != sources.lastIndex) {
                container.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    )
                    setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.color_divider))
                })
            }
        }
    }

    private fun buildSourceRow(source: DevialetSource, glyph: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.bg_source_row)
        }

        val icon = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) }
            gravity = Gravity.CENTER
            text = glyph
            textSize = 16f
            setBackgroundResource(if (source.isSelected) R.drawable.shape_source_icon_selected else R.drawable.shape_source_icon)
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (source.isSelected) R.color.color_copper_bright else R.color.color_text_dim
                )
            )
        }

        val name = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = source.name
            textSize = 14.5f
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (source.isSelected) R.color.color_copper_bright else R.color.color_text
                )
            )
        }

        val check = TextView(this).apply {
            text = "✓"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.color_copper_bright))
            visibility = if (source.isSelected) View.VISIBLE else View.INVISIBLE
        }

        row.addView(icon)
        row.addView(name)
        row.addView(check)

        row.setOnClickListener {
            // Snappy immediate feedback, same as before - the real
            // confirmation still comes from the amp's next status broadcast.
            txtTriggerName.text = source.name
            txtDialSource.text = source.name
            txtTriggerIcon.text = glyph
            dismissSourceSheet()
            requireIp {
                network.submit { runCatching { controller.selectSource(source.index) } }
            }
        }

        return row
    }

    override fun onResume() {
        super.onResume()
        statusListener.start()
        modelNameResolver.start() // already begins the fast retry burst fresh - see AmpModelNameResolver.start()
        cardRefreshHandler.postDelayed(cardRefreshRunnable, 2_000L)
    }

    override fun onPause() {
        super.onPause()
        statusListener.stop()
        modelNameResolver.stop()
        stopVolumeRepeat() // don't keep firing volume changes while backgrounded
        cardRefreshHandler.removeCallbacks(cardRefreshRunnable)
        pickerRefreshRunnable?.let { pickerRefreshHandler.removeCallbacks(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        network.shutdownNow()
    }
}
