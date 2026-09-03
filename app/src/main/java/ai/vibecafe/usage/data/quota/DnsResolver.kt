package ai.vibecafe.usage.data.quota

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 系统 DNS + DoH 兜底解析器：模拟器与部分大陆网络下系统解析会间歇性整体失效
 * （所有域名报 No address associated with hostname），此时改用固定 IP 的公共
 * DoH 服务查询 A 记录（端点本身无需解析，不受 DNS 故障影响）。
 * 结果带短 TTL 缓存，避免每次请求都多一次 DoH 往返。
 */
object DnsResolver : Dns {

    /** AliDNS 国内可达；Cloudflare 1.1.1.1 作国际兜底，均为 JSON 接口的纯 IP 端点 */
    private val DOH_ENDPOINTS = listOf(
        "https://223.5.5.5/resolve?name=%s&type=1",
        "https://1.1.1.1/dns-query?name=%s&type=1",
    )

    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private data class Entry(val addresses: List<InetAddress>, val expireAt: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        cache[hostname]?.let { if (it.expireAt > now) return it.addresses }
        try {
            val result = Dns.SYSTEM.lookup(hostname)
            if (result.isNotEmpty()) {
                cache[hostname] = Entry(result, now + 5 * 60_000)
                return result
            }
        } catch (_: UnknownHostException) {
            // 系统 DNS 失效 → DoH 兜底
        }
        for (endpoint in DOH_ENDPOINTS) {
            try {
                val req = Request.Builder()
                    .url(endpoint.format(hostname))
                    .header("accept", "application/dns-json")
                    .build()
                dohClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val answer = JSONObject(resp.body?.string().orEmpty())
                        .optJSONArray("Answer") ?: return@use
                    val ips = (0 until answer.length()).mapNotNull { i ->
                        val item = answer.getJSONObject(i)
                        val data = item.optString("data")
                        if (item.optInt("type") == 1 &&
                            Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(data)
                        ) InetAddress.getByName(data) else null
                    }
                    if (ips.isNotEmpty()) {
                        cache[hostname] = Entry(ips, now + 5 * 60_000)
                        return ips
                    }
                }
            } catch (_: Exception) {
                // 换下一个 DoH 服务
            }
        }
        throw UnknownHostException("Unable to resolve host \"$hostname\" (系统 DNS 与 DoH 均失败)")
    }
}
