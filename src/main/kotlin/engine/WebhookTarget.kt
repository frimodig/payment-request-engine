package engine

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * Decides whether the engine may call a webhook URL a partner registered, so a partner cannot make the
 * engine call something inside the bank (SSRF, threat model B3).
 *
 * Only https without credentials in the URL, to a fully qualified host name (never an IP literal) whose
 * every address is public. Refusing literals sidesteps forms parsers disagree on: Java reads `0177.0.0.1` as
 * 177.0.0.1, curl as 127.0.0.1. Run it when the URL is registered and again before each delivery. Delivery
 * connects to the [Allowed.addresses] checked here, never resolving the host again, and does not follow
 * redirects: otherwise DNS rebinding or a redirect reaches an internal address after the check passed.
 *
 * Reasons are for the engine's own logs. Tell the partner only that the URL is not allowed, or the
 * difference between "not public" and "does not resolve" maps the bank's internal names.
 */
sealed interface WebhookTarget {
    data class Allowed(val uri: URI, val addresses: List<InetAddress>) : WebhookTarget

    /** Never call this URL. */
    data class Blocked(val reason: String) : WebhookTarget

    /** The host did not resolve. Before a delivery, retry later; at registration, refuse. */
    data class Unresolved(val reason: String) : WebhookTarget

    companion object {
        val SYSTEM_DNS: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }

        fun check(url: String, resolve: (String) -> List<InetAddress> = SYSTEM_DNS): WebhookTarget {
            val uri = runCatching { URI(url) }.getOrNull() ?: return Blocked("not a valid URL")
            if (!"https".equals(uri.scheme, ignoreCase = true)) return Blocked("only https is allowed")
            if (uri.rawUserInfo != null) return Blocked("credentials in the URL are not allowed")
            val host = uri.host ?: return Blocked("the URL has no valid host")
            if (!isQualifiedHostName(host)) return Blocked("use a fully qualified host name, not an IP address")
            val addresses = try {
                // With a trailing dot the name is absolute: the resolver adds none of its search domains.
                resolve(host.removeSuffix(".") + ".")
            } catch (e: UnknownHostException) {
                return Unresolved("the host does not resolve")
            }
            if (addresses.isEmpty()) return Unresolved("the host does not resolve")
            if (!addresses.all(::isPublic)) return Blocked("the host resolves to an address that is not public")
            return Allowed(uri, addresses)
        }

        /**
         * At least two labels of letters, digits and hyphens, each 1 to 63 characters, at most 253 in all;
         * the last label starts with a letter, so no IP literal passes. A single label such as `vault` is
         * refused: it is only ever meant as an internal name.
         */
        private fun isQualifiedHostName(host: String): Boolean {
            val name = host.removeSuffix(".")
            val labels = name.split(".")
            return name.length <= 253 && labels.size >= 2 && labels.all { it.matches(LABEL) } &&
                labels.last().first().isLetter()
        }

        private val LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?", RegexOption.IGNORE_CASE)

        private fun isPublic(address: InetAddress): Boolean {
            val a = mappedIpv4(address) ?: address
            val javaKnowsItIsInternal = a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
                a.isSiteLocalAddress || a.isMulticastAddress
            return !javaKnowsItIsInternal && NOT_PUBLIC.none { it.contains(a) }
        }

        /** `::ffff:a.b.c.d` as its IPv4 address, so it is judged by the IPv4 rules. */
        private fun mappedIpv4(address: InetAddress): InetAddress? {
            val b = address.address
            val mapped = address is Inet6Address && (0..9).all { b[it] == 0.toByte() } &&
                b[10] == 0xff.toByte() && b[11] == 0xff.toByte()
            return if (mapped) InetAddress.getByAddress(b.copyOfRange(12, 16)) else null
        }

        /**
         * Special-purpose ranges that Java's checks above miss, from the IANA IPv4 and IPv6 special-purpose
         * registries. The IPv6 transition ranges are blocked whole because they can carry an internal IPv4
         * address.
         */
        internal val NOT_PUBLIC = listOf(
            "0.0.0.0/8", // "this network"
            "100.64.0.0/10", // carrier-grade NAT, used inside some clouds
            "192.0.0.0/24", // protocol assignments
            "192.88.99.0/24", // 6to4 relay anycast (deprecated)
            "192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24", // documentation
            "198.18.0.0/15", // benchmarking
            "240.0.0.0/4", // reserved, including broadcast
            "::/96", // IPv4-compatible (deprecated)
            "::ffff:0:0:0/96", // IPv4-translated
            "64:ff9b::/96", "64:ff9b:1::/48", // NAT64
            "100::/64", // discard
            "100:0:0:1::/64", // dummy prefix
            "2001::/23", // IETF protocol assignments, including Teredo
            "2001:db8::/32", "3fff::/20", // documentation
            "2002::/16", // 6to4
            "5f00::/16", // SRv6 segment identifiers
            "fc00::/7", // unique local
        ).map(::Cidr)
    }
}

internal class Cidr(val spec: String) {
    private val network: ByteArray
    private val prefixBits: Int

    init {
        val (address, bits) = spec.split("/")
        network = InetAddress.getByName(address).address
        prefixBits = bits.toInt()
        check(prefixBits <= network.size * 8) { "Bad range $spec" }
    }

    fun contains(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != network.size) return false
        return (0 until prefixBits).all { bit ->
            val mask = 0x80 ushr (bit % 8)
            (bytes[bit / 8].toInt() and mask) == (network[bit / 8].toInt() and mask)
        }
    }

    /** The first and last address of the range. */
    fun bounds(): Pair<InetAddress, InetAddress> {
        val low = network.copyOf()
        val high = network.copyOf()
        for (bit in prefixBits until network.size * 8) {
            val mask = 0x80 ushr (bit % 8)
            low[bit / 8] = (low[bit / 8].toInt() and mask.inv()).toByte()
            high[bit / 8] = (high[bit / 8].toInt() or mask).toByte()
        }
        return InetAddress.getByAddress(low) to InetAddress.getByAddress(high)
    }
}
