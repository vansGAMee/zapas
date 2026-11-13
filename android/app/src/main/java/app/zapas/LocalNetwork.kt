package app.zapas

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL

data class LocalComputer(val address: String, val serverId: String)

/** All PC traffic stays on the physical home network, even with a VPN enabled. */
class LocalNetwork(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    fun network(): Network? = manager.allNetworks.firstOrNull { n ->
        val caps = manager.getNetworkCapabilities(n)
        caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    fun open(url: URL): HttpURLConnection {
        // Loopback is also used by USB adb reverse during device verification.
        val n = if (url.host == "127.0.0.1" || url.host == "localhost") null else network()
        return (n?.openConnection(url, Proxy.NO_PROXY) ?: url.openConnection(Proxy.NO_PROXY)) as HttpURLConnection
    }

    fun phoneAddress(): String = network()?.let { n -> manager.getLinkProperties(n)?.linkAddresses
        ?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress }.orEmpty()

    fun error(e: Exception, address: String): String = when (e) {
        is java.net.ConnectException, is SocketTimeoutException, is java.net.NoRouteToHostException ->
            if (network() == null) "Подключи телефон к домашнему Wi‑Fi. ПК должен быть в той же сети."
            else "ПК по адресу $address недоступен. Проверь, что Запас открыт на ПК и устройства в одной сети, без гостевого Wi‑Fi. Адрес телефона: ${phoneAddress()}."
        is java.net.UnknownHostException -> "Не найден адрес ПК. Нажми «Найти компьютер» или введи адрес из Запаса на ПК."
        is SecurityException -> "Android запретил доступ к сети. Разреши Запасу доступ к локальной сети в настройках телефона."
        else -> if (e.message?.contains("EPERM") == true || e.message?.contains("EACCES") == true)
            "Доступ к домашней сети заблокирован. Проверь разрешения Запаса и настройку VPN «Блокировать соединения без VPN»."
        else e.message?.take(300) ?: "Не удалось подключиться. Нажми «Найти компьютер» и повтори."
    }

    suspend fun discover(): List<LocalComputer> = withContext(Dispatchers.IO) {
        val n = network() ?: return@withContext emptyList()
        val targets = mutableSetOf("255.255.255.255")
        manager.getLinkProperties(n)?.linkAddresses?.forEach { link ->
            if (link.address is Inet4Address && link.prefixLength in 1..30) {
                val raw = link.address.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 255) }
                val broadcast = raw or ((1L shl (32-link.prefixLength))-1)
                targets += (3 downTo 0).joinToString(".") { ((broadcast shr (8*it)) and 255).toString() }
            }
        }
        val found = linkedMapOf<String, LocalComputer>()
        DatagramSocket().use { socket ->
            n.bindSocket(socket)
            socket.broadcast = true
            socket.soTimeout = 300
            val request = "ZAPAS_DISCOVER_V1".toByteArray()
            repeat(2) {
                targets.forEach { target ->
                    runCatching { socket.send(DatagramPacket(request, request.size, InetAddress.getByName(target), 9474)) }
                }
                val deadline = System.nanoTime() + 1_500_000_000L
                while (System.nanoTime() < deadline) {
                    currentCoroutineContext().ensureActive()
                    val packet = DatagramPacket(ByteArray(1024), 1024)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    if (!packet.address.isSiteLocalAddress && !packet.address.isLoopbackAddress) continue
                    runCatching {
                        val data = JSONObject(String(packet.data, 0, packet.length))
                        val port = data.getInt("port"); val id = data.getString("server_id")
                        if (data.optString("service") == "zapas" && data.optInt("version") == 1 && port in 1..65535 && id.matches(Regex("[a-f0-9]{32}"))) {
                            found[id] = LocalComputer("http://${packet.address.hostAddress}:$port", id)
                        }
                    }
                }
            }
        }
        found.values.toList()
    }
}
