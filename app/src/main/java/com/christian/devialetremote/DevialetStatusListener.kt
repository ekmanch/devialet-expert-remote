package com.christian.devialetremote

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Listens for the amp's periodic UDP status broadcasts (~once per second) on
 * port 45454 and parses them into a DevialetStatus. Runs on its own daemon thread.
 */
class DevialetStatusListener(
    private val onStatus: (DevialetStatus, senderIp: String) -> Unit
) {
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.bind(InetSocketAddress("0.0.0.0", DevialetController.STATUS_PORT))
                s.broadcast = true
                socket = s
                val buf = ByteArray(2048)
                while (running) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(packet)
                    } catch (e: Exception) {
                        if (!running) break else continue
                    }
                    parseStatus(packet.data, packet.length)?.let {
                        onStatus(it, packet.address?.hostAddress ?: "")
                    }
                }
            } catch (e: Exception) {
                // Bind/setup failed - the app just won't get live status; direct
                // control commands (which don't need this listener) still work.
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        socket?.close()
        thread = null
    }

    private fun parseStatus(data: ByteArray, length: Int): DevialetStatus? {
        if (length < 566) return null
        return try {
            val name = String(data, 19, 31, Charsets.UTF_8).trim('\u0000', ' ')
            val sourceIndex = (data[563].toInt() and 0x3C) shr 2
            val sources = ArrayList<DevialetSource>(30)
            for (i in 0 until 30) {
                val enabledChar = (data[52 + i * 17].toInt() and 0xFF).toChar()
                val isEnabled = enabledChar == '1'
                val srcName = String(data, 53 + i * 17, 16, Charsets.UTF_8).trim('\u0000', ' ')
                sources.add(DevialetSource(srcName, i, isEnabled, i == sourceIndex))
            }
            val power = (data[562].toInt() and 0x80) != 0
            val muted = (data[563].toInt() and 0x02) != 0
            val volume = data[565].toInt() and 0xFF
            DevialetStatus(name, power, muted, volume, sourceIndex, sources)
        } catch (e: Exception) {
            null
        }
    }
}
