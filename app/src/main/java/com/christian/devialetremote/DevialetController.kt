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
    }

    private val packetCounter = AtomicInteger(0)
    private val commandCounter = AtomicInteger(0)

    // ---- CRC16/CCITT-FALSE (poly 0x1021, init 0xFFFF) ----
    private fun crc16(data: ByteArray, length: Int): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
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

        val crc = crc16(data, 12)
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
        val outVal = 0x4000 or (index shl 5)
        val hi = (outVal shr 8) and 0xFF
        val lo = if (index > 7) (outVal and 0xFF) shr 1 else outVal and 0xFF
        sendTwice(0x00, 0x05, hi, lo)
    }
}
