package com.example.sip

import android.content.Context
import android.net.ConnectivityManager
import com.example.domain.model.SipTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.random.Random

data class SrvEndpoint(
    val transport: SipTransport,
    val targetHost: String,
    val port: Int,
    val priority: Int,
    val weight: Int
)

/**
 * Lightweight pure-Kotlin RFC 2782 DNS SRV resolver over UDP (port 53).
 * Queries `_sips._tcp.<domain>`, `_sip._tcp.<domain>`, and `_sip._udp.<domain>`
 * to auto-discover SIP proxy endpoints without requiring manual transport selection.
 */
class DnsSrvResolver(private val context: Context) {

    suspend fun resolveSipSrvRecords(domain: String): List<SrvEndpoint> = withContext(Dispatchers.IO) {
        val cleanDomain = domain.trim().removePrefix("sip:").substringBefore(":")
        if (cleanDomain.isBlank() || isNumericIp(cleanDomain)) return@withContext emptyList()

        val dnsServers = getSystemDnsServers()
        val queries = listOf(
            "_sip._udp.$cleanDomain" to SipTransport.UDP,
            "_sip._tcp.$cleanDomain" to SipTransport.TCP,
            "_sips._tcp.$cleanDomain" to SipTransport.TLS
        )

        val discovered = mutableListOf<SrvEndpoint>()
        for ((srvName, transport) in queries) {
            for (dnsServer in dnsServers) {
                val records = runCatching { querySrvRecord(dnsServer, srvName, transport) }.getOrNull()
                if (!records.isNullOrEmpty()) {
                    discovered.addAll(records)
                    break
                }
            }
        }
        discovered.sortedWith(compareBy<SrvEndpoint> { it.priority }.thenByDescending { it.weight })
    }

    private fun getSystemDnsServers(): List<InetAddress> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val linkProps = if (activeNetwork != null) cm.getLinkProperties(activeNetwork) else null
        val systemDns = linkProps?.dnsServers?.filter { it.address.size == 4 }.orEmpty()
        return systemDns.ifEmpty {
            listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("1.1.1.1"))
        }
    }

    private fun querySrvRecord(
        dnsServer: InetAddress,
        qname: String,
        transport: SipTransport
    ): List<SrvEndpoint> {
        val txId = Random.nextInt(1, 65535)
        val packetBytes = encodeDnsSrvQuery(txId, qname)

        DatagramSocket().use { socket ->
            socket.soTimeout = 1600
            socket.send(DatagramPacket(packetBytes, packetBytes.size, dnsServer, 53))
            val rxBuffer = ByteArray(1500)
            val rxPacket = DatagramPacket(rxBuffer, rxBuffer.size)
            socket.receive(rxPacket)
            return parseDnsSrvResponse(rxPacket.data, rxPacket.length, txId, transport)
        }
    }

    private fun encodeDnsSrvQuery(txId: Int, qname: String): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeShort(txId)
        dos.writeShort(0x0100) // Standard query with recursion desired
        dos.writeShort(1)      // QDCOUNT = 1
        dos.writeShort(0)      // ANCOUNT = 0
        dos.writeShort(0)      // NSCOUNT = 0
        dos.writeShort(0)      // ARCOUNT = 0

        for (label in qname.split('.').filter { it.isNotEmpty() }) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            dos.writeByte(bytes.size)
            dos.write(bytes)
        }
        dos.writeByte(0)       // Root terminator
        dos.writeShort(33)     // QTYPE = SRV (33)
        dos.writeShort(1)      // QCLASS = IN (1)
        dos.flush()
        return baos.toByteArray()
    }

    private fun parseDnsSrvResponse(
        data: ByteArray,
        length: Int,
        expectedTxId: Int,
        transport: SipTransport
    ): List<SrvEndpoint> {
        if (length < 12) return emptyList()
        val buf = ByteBuffer.wrap(data, 0, length)
        val txId = buf.short.toInt() and 0xFFFF
        if (txId != expectedTxId) return emptyList()

        buf.short // flags
        val qdCount = buf.short.toInt() and 0xFFFF
        val anCount = buf.short.toInt() and 0xFFFF
        buf.short // nsCount
        buf.short // arCount

        var offset = 12
        repeat(qdCount) {
            offset = skipDnsName(data, offset)
            offset += 4 // QTYPE + QCLASS
            if (offset > length) return emptyList()
        }

        val endpoints = mutableListOf<SrvEndpoint>()
        repeat(anCount) {
            offset = skipDnsName(data, offset)
            if (offset + 10 > length) return endpoints
            val rType = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val rdLength = ((data[offset + 8].toInt() and 0xFF) shl 8) or (data[offset + 9].toInt() and 0xFF)
            offset += 10
            if (offset + rdLength > length) return endpoints

            if (rType == 33 && rdLength >= 7) { // SRV record
                val priority = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                val weight = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
                val port = ((data[offset + 4].toInt() and 0xFF) shl 8) or (data[offset + 5].toInt() and 0xFF)
                val target = readDnsName(data, offset + 6).first.trimEnd('.')
                if (target.isNotBlank() && port > 0) {
                    endpoints.add(SrvEndpoint(transport, target, port, priority, weight))
                }
            }
            offset += rdLength
        }
        return endpoints
    }

    private fun skipDnsName(data: ByteArray, start: Int): Int {
        var idx = start
        while (idx < data.size) {
            val len = data[idx].toInt() and 0xFF
            if (len == 0) return idx + 1
            if ((len and 0xC0) == 0xC0) return idx + 2
            idx += 1 + len
        }
        return idx
    }

    private fun readDnsName(data: ByteArray, start: Int, depth: Int = 0): Pair<String, Int> {
        if (depth > 8) return "" to start
        val sb = StringBuilder()
        var idx = start
        var jumped = false
        var endOffset = start
        while (idx < data.size) {
            val len = data[idx].toInt() and 0xFF
            if (len == 0) {
                if (!jumped) endOffset = idx + 1
                break
            }
            if ((len and 0xC0) == 0xC0 && idx + 1 < data.size) {
                val pointer = ((len and 0x3F) shl 8) or (data[idx + 1].toInt() and 0xFF)
                if (!jumped) endOffset = idx + 2
                val target = readDnsName(data, pointer, depth + 1).first
                if (sb.isNotEmpty() && target.isNotEmpty()) sb.append('.')
                sb.append(target)
                jumped = true
                break
            } else {
                idx++
                if (idx + len > data.size) break
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(data, idx, len, Charsets.US_ASCII))
                idx += len
                if (!jumped) endOffset = idx
            }
        }
        return sb.toString() to endOffset
    }

    private fun isNumericIp(host: String): Boolean {
        return host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$""")) || host.contains(':')
    }
}
