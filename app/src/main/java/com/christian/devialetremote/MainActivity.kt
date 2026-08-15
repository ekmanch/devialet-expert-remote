package com.christian.devialetremote

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var controller: DevialetController
    private lateinit var statusListener: DevialetStatusListener
    private lateinit var network: ExecutorService

    private lateinit var editIp: EditText
    private lateinit var txtStatus: TextView
    private lateinit var txtVolumeDb: TextView
    private lateinit var seekVolume: SeekBar
    private lateinit var btnMute: Button
    private lateinit var btnPower: Button
    private lateinit var sourcesContainer: LinearLayout

    private var userIsDraggingSlider = false
    private var isMuted = false
    private var isPoweredOn = true


    // Slider maps progress 0..90 to dB range -60..-15, in 0.5dB steps
    private fun progressToDb(progress: Int): Double = (progress * 0.5) - 60.0
    private fun dbToProgress(db: Double): Int = ((db + 60.0) * 2).toInt().coerceIn(0, 90)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("devialet_remote", MODE_PRIVATE)
        network = Executors.newSingleThreadExecutor()

        editIp = findViewById(R.id.editIp)
        txtStatus = findViewById(R.id.txtStatus)
        txtVolumeDb = findViewById(R.id.txtVolumeDb)
        seekVolume = findViewById(R.id.seekVolume)
        btnMute = findViewById(R.id.btnMute)
        btnPower = findViewById(R.id.btnPower)
        sourcesContainer = findViewById(R.id.sourcesContainer)

        val savedIp = prefs.getString("amp_ip", "") ?: ""
        editIp.setText(savedIp)
        controller = DevialetController(savedIp)

        findViewById<Button>(R.id.btnSaveIp).setOnClickListener {
            val ip = editIp.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "Enter an IP address first", Toast.LENGTH_SHORT).show()
            } else {
                prefs.edit().putString("amp_ip", ip).apply()
                controller.deviceIp = ip
                Toast.makeText(this, "Saved. Talking to $ip", Toast.LENGTH_SHORT).show()
            }
        }

        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val db = progressToDb(progress)
                txtVolumeDb.text = String.format("%.1f dB", db)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { userIsDraggingSlider = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userIsDraggingSlider = false
                val db = progressToDb(seekBar?.progress ?: 0)
                sendVolume(db)
            }
        })

        findViewById<Button>(R.id.btnVolUp).setOnClickListener {
            seekVolume.progress = (seekVolume.progress + 1).coerceAtMost(seekVolume.max)
            sendVolume(progressToDb(seekVolume.progress))
        }
        findViewById<Button>(R.id.btnVolDown).setOnClickListener {
            seekVolume.progress = (seekVolume.progress - 1).coerceAtLeast(0)
            sendVolume(progressToDb(seekVolume.progress))
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

        // Listens for the amp's own status broadcasts to show live volume/mute/
        // power/source and to populate the source buttons. Purely informational -
        // control buttons above work even if this never receives anything.
        statusListener = DevialetStatusListener { status, senderIp ->
            runOnUiThread { applyStatus(status, senderIp) }
        }
    }

    private fun requireIp(action: () -> Unit) {
        if (controller.deviceIp.isBlank()) {
            Toast.makeText(this, "Enter and save the amp's IP address first", Toast.LENGTH_SHORT).show()
        } else {
            action()
        }
    }

    private fun sendVolume(db: Double) {
        requireIp {
            network.submit { runCatching { controller.setVolumeDb(db) } }
        }
    }

    private fun updateMuteButton() {
        btnMute.text = if (isMuted) "Unmute" else "Mute"
    }

    private fun updatePowerButton() {
        btnPower.text = if (isPoweredOn) "Power Off" else "Power On"
    }

    private fun applyStatus(status: DevialetStatus, senderIp: String) {
        val savedIp = editIp.text.toString().trim()
        if (savedIp.isNotEmpty() && senderIp != savedIp) return // ignore other amps on the LAN

        txtStatus.text = "${status.deviceName} @ $senderIp\n" +
                "Power: ${if (status.powerOn) "On" else "Off"}  |  " +
                "Muted: ${status.muted}  |  " +
                "Source: ${status.currentSourceName}"

        isMuted = status.muted
        isPoweredOn = status.powerOn
        updateMuteButton()
        updatePowerButton()

        if (!userIsDraggingSlider) {
            seekVolume.progress = dbToProgress(status.volumeDb)
            txtVolumeDb.text = String.format("%.1f dB", status.volumeDb)
        }

        rebuildSourceButtons(status)
    }

    private fun rebuildSourceButtons(status: DevialetStatus) {
        if (sourcesContainer.tag == status.enabledSources.map { it.name }) return // avoid rebuilding every second
        sourcesContainer.removeAllViews()
        sourcesContainer.tag = status.enabledSources.map { it.name }
        for (source in status.enabledSources) {
            val button = Button(this).apply {
                text = if (source.isSelected) "${source.name}  (active)" else source.name
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
    }

    override fun onPause() {
        super.onPause()
        statusListener.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        network.shutdownNow()
    }
}
