package com.vclient.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Asks a Minecraft server directly whether it is up, so the Home screen works even when the VYRN
 * backend is down. Both calls never throw: a failure is simply "offline".
 */
object ServerPing {
    private const val TIMEOUT_MS = 3500

    suspend fun status(java: Endpoint, bedrock: Endpoint): ServerStatus = withContext(Dispatchers.IO) {
        ServerStatus(java = javaPing(java), bedrock = bedrockPing(bedrock))
    }

    /** Java Edition "Server List Ping" over TCP. */
    fun javaPing(server: Endpoint): EditionStatus = try {
        val started = System.currentTimeMillis()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(server.host, server.port), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            val handshake = ByteArrayOutputStream().apply {
                writeVarInt(0x00)
                writeVarInt(767)
                val host = server.host.toByteArray()
                writeVarInt(host.size)
                write(host)
                write(server.port shr 8)
                write(server.port and 0xff)
                writeVarInt(1)
            }.toByteArray()
            val out = socket.getOutputStream()
            out.write(ByteArrayOutputStream().apply { writeVarInt(handshake.size); write(handshake) }.toByteArray())
            out.write(byteArrayOf(1, 0)) // status request: length 1, id 0
            out.flush()

            val input = DataInputStream(socket.getInputStream())
            readVarInt(input) // packet length
            readVarInt(input) // packet id
            val length = readVarInt(input)
            if (length <= 0 || length > 1_000_000) return EditionStatus(false)
            val bytes = ByteArray(length)
            input.readFully(bytes)
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val players = json.optJSONObject("players")
            EditionStatus(
                online = true,
                players = players?.optInt("online") ?: 0,
                maxPlayers = players?.optInt("max") ?: 0,
                version = json.optJSONObject("version")?.optString("name").orEmpty(),
                latencyMs = System.currentTimeMillis() - started,
            )
        }
    } catch (e: Exception) {
        EditionStatus(false)
    }

    /** Bedrock Edition RakNet "unconnected ping" over UDP. */
    fun bedrockPing(server: Endpoint): EditionStatus = try {
        val started = System.currentTimeMillis()
        DatagramSocket().use { socket ->
            socket.soTimeout = TIMEOUT_MS
            val ping = ByteArray(33)
            ping[0] = 0x01
            val time = System.currentTimeMillis()
            for (i in 0 until 8) ping[1 + i] = (time shr (56 - 8 * i)).toByte()
            RAKNET_MAGIC.copyInto(ping, 9)
            socket.send(DatagramPacket(ping, ping.size, InetAddress.getByName(server.host), server.port))
            val buffer = ByteArray(1500)
            val reply = DatagramPacket(buffer, buffer.size)
            socket.receive(reply)
            if (reply.length < 35 || buffer[0] != 0x1c.toByte()) return EditionStatus(false)
            val fields = String(buffer, 35, reply.length - 35, Charsets.UTF_8).split(';')
            EditionStatus(
                online = true,
                players = fields.getOrNull(4)?.toIntOrNull() ?: 0,
                maxPlayers = fields.getOrNull(5)?.toIntOrNull() ?: 0,
                version = fields.getOrNull(3).orEmpty(),
                latencyMs = System.currentTimeMillis() - started,
            )
        }
    } catch (e: Exception) {
        EditionStatus(false)
    }

    private val RAKNET_MAGIC = byteArrayOf(
        0x00, 0xff.toByte(), 0xff.toByte(), 0x00, 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(),
        0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0x12, 0x34, 0x56, 0x78,
    )

    private fun ByteArrayOutputStream.writeVarInt(value: Int) {
        var v = value
        do {
            var b = v and 0x7f
            v = v ushr 7
            if (v != 0) b = b or 0x80
            write(b)
        } while (v != 0)
    }

    private fun readVarInt(input: DataInputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = input.readUnsignedByte()
            result = result or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 35) throw java.io.IOException("VarInt too large")
        }
    }
}
