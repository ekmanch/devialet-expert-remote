package com.christian.devialetremote

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln

/**
 * Sends control commands to a Devialet Expert / Expert Pro amplifier over the
 * local network, using the reverse-engineered UDP protocol (community-documented,
 * not an official Devialet API). No cloud, no auth - amp and phone just need to
 * be on the same LAN.
 *
 * Protocol reference: gnulabis/devimote, jprouty/devialet_expert (Home Assistant
 * integration), andrewmgrossman/devialet_expert_remote.
 */
class DevialetController(@Volatile var deviceIp: String) {

    companion object {
        const val STATUS_PORT = 45454   // amp -> us, broadcasts status ~1x/sec
        const val COMMAND_PORT = 45455  // us -> amp, control commands

        // Channel switching is NON-LINEAR: the source index reported in the
        // amp's status broadcast (DevialetSource.index, 0-14) does not match
        // the command value the amp expects when selecting that source. This
        // maps status-broadcast index -> command value, per community
        // reverse-engineering (gnulabis/devimote, andrewmgrossman/devialet_expert_remote).
        // Channel 1 (Phono) isn't in this map - it needs hardcoded bytes, see below.
        private val SOURCE_COMMAND_VALUE = mapOf(
            0 to -1,  // Optical 1
            2 to 0,   // UPnP
            3 to 3,   // Roon Ready
            4 to 4,   // AirPlay
            5 to 5,   // Spotify
            14 to 14  // Air (Bluetooth)
        )

        // The amp doesn't apply a consistent startup volume across inputs on
        // its own (observed -40dB on Optical 1 vs -38dB on other sources) -
        // this is the amp's own per-input volume memory, not something this
        // remote sets elsewhere. Forcing this after every source switch keeps
        // the volume predictable/consistent regardless of source.
        private const val SOURCE_SWITCH_VOLUME_DB = -40.0
    }

    private val packetCounter = AtomicInteger(0)
    private val commandCounter = AtomicInteger(0)

    // ---- CRC16/CCITT-FALSE (poly 0x1021, init 0xFFFF) ----
    private fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (i in 0 until 12) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc and 0xFFFF
    }

    // ---- dB value -> Devialet's custom 16-bit volume word ----
    private fun dbConvert(dbValue: Double): Int {
        val dbAbs = abs(dbValue)
        return when {
            dbAbs == 0.0 -> 0
            dbAbs == 0.5 -> 0x3F00
            else -> {
                val shift = ceil(1 + ln(dbAbs) / ln(2.0)).toInt()
                (256 shr shift) + dbConvert(dbAbs - 0.5)
            }
        }
    }

    private fun nextCounter(counter: AtomicInteger): Int {
        val value = counter.getAndUpdate { if (it >= 0xFFFF) 0 else it + 1 }
        return value and 0xFFFF
    }

    private fun buildCommand(byte6: Int, byte7: Int, byte8: Int = 0, byte9: Int = 0): ByteArray {
        val data = ByteArray(142)
        data[0] = 0x44
        data[1] = 0x72

        val pCount = nextCounter(packetCounter)
        val cCount = nextCounter(commandCounter)

        data[2] = ((pCount shr 8) and 0xFF).toByte()
        data[3] = (pCount and 0xFF).toByte()
        data[4] = ((cCount shr 8) and 0xFF).toByte()
        data[5] = (cCount and 0xFF).toByte()

        data[6] = byte6.toByte()
        data[7] = byte7.toByte()
        data[8] = byte8.toByte()
        data[9] = byte9.toByte()

        val crc = crc16(data)
        data[12] = ((crc shr 8) and 0xFF).toByte()
        data[13] = (crc and 0xFF).toByte()
        return data
    }

    /** Sends a command twice (matches the amp's expected retransmit behavior). Must be called off the main thread. */
    private fun sendTwice(byte6: Int, byte7: Int, byte8: Int = 0, byte9: Int = 0) {
        DatagramSocket().use { socket ->
            val address = InetAddress.getByName(deviceIp)
            repeat(2) {
                val data = buildCommand(byte6, byte7, byte8, byte9)
                socket.send(DatagramPacket(data, data.size, address, COMMAND_PORT))
            }
        }
    }

    fun setPower(on: Boolean) = sendTwice(if (on) 1 else 0, 0x01)

    fun setMute(muted: Boolean) = sendTwice(if (muted) 1 else 0, 0x07)

    /** maxDb caps how loud this app will ever ask for - safety margin, adjust if you want more headroom. */
    fun setVolumeDb(dbIn: Double, maxDb: Double = -15.0) {
        val db = dbIn.coerceAtMost(maxDb)
        var vol = dbConvert(db)
        if (db < 0) vol = vol or 0x8000
        sendTwice(0x00, 0x04, (vol shr 8) and 0xFF, vol and 0xFF)
    }

    fun selectSource(index: Int) {
        // Phono (status index 1) doesn't follow the standard formula - the
        // official app sends these hardcoded bytes instead (discovered via
        // Wireshark analysis, see gnulabis/devimote issue #2).
        if (index == 1) {
            sendTwice(0x00, 0x05, 0x3F, 0x80)
        } else {
            // Fall back to passing the raw index through for anything not in
            // the known map (e.g. custom/uncommon inputs) - matches prior
            // behavior for those, may need adjusting per-amp/firmware.
            val cmdValue = SOURCE_COMMAND_VALUE[index] ?: index
            val outVal = 0x4000 or (cmdValue shl 5)
            val hi = (outVal shr 8) and 0xFF
            val lo = if (cmdValue > 7) (outVal and 0xFF) shr 1 else outVal and 0xFF
            sendTwice(0x00, 0x05, hi, lo)
        }
        // Applies to every source, not just some - overrides whatever
        // startup volume the amp would otherwise pick for this input.
        setVolumeDb(SOURCE_SWITCH_VOLUME_DB)
    }

    // ---- Not yet wired: SAM / Night Mode ----
    //
    // The UI has switches for these (MainActivity's samSwitch/nightSwitch),
    // but on purpose they only flip local UI state right now and never call
    // down into here - the UDP command bytes for SAM and Night Mode haven't
    // been reverse-engineered yet (unlike power/mute/volume/source above,
    // which came from gnulabis/devimote and jprouty/devialet_expert). Sending
    // guessed bytes risks the amp doing something unintended, so nothing goes
    // out over the wire for these two until the real values are confirmed
    // (packet-sniffing the official app while toggling each setting is the
    // most direct way to get them).
    //
    // Once known, wiring these up is the same shape as setMute/setPower
    // above - a single sendTwice(byte6, byte7) call - and MainActivity's two
    // switch listeners just need a `network.submit { ... }` call added,
    // exactly like btnMute's and btnPower's click listeners already do.
    //
    // fun setSam(on: Boolean) = sendTwice(/* TODO: byte6 */, /* TODO: byte7 */)
    // fun setNightMode(on: Boolean) = sendTwice(/* TODO: byte6 */, /* TODO: byte7 */)
}
