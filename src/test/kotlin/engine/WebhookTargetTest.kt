package engine

import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WebhookTargetTest {
    private val url = "https://hooks.partner.example/payments"

    private fun dns(vararg records: String): (String) -> List<InetAddress> =
        { records.map { InetAddress.getByName(it) } }

    private fun assertBlocked(url: String, resolve: (String) -> List<InetAddress>): WebhookTarget.Blocked =
        assertIs<WebhookTarget.Blocked>(WebhookTarget.check(url, resolve), url)

    private fun assertNotPublic(vararg records: String) {
        val reason = assertBlocked(url, dns(*records)).reason
        assertTrue("not public" in reason, "${records.toList()}: $reason")
    }

    private fun assertAllowed(vararg records: String) {
        assertIs<WebhookTarget.Allowed>(WebhookTarget.check(url, dns(*records)), records.toList().toString())
    }

    @Test
    fun `an https URL whose host resolves only to public addresses is allowed, with the addresses to connect to`() {
        val result = WebhookTarget.check(url, dns("8.8.8.8", "2001:4860:4860::8888"))

        assertIs<WebhookTarget.Allowed>(result)
        assertEquals(listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("2001:4860:4860::8888")), result.addresses)
    }

    @Test
    fun `localhost and loopback addresses are blocked`() {
        assertBlocked("https://localhost/hook", dns("127.0.0.1"))
        assertNotPublic("127.10.0.1")
        assertNotPublic("::1")
    }

    @Test
    fun `private, link-local and multicast addresses are blocked`() {
        listOf(
            "10.0.0.5", "172.16.0.1", "172.31.255.255", "192.168.1.1", // private networks
            "169.254.169.254", // cloud metadata service (link-local)
            "224.0.0.1", "::", "fe80::1", "ff02::1",
        ).forEach { assertNotPublic(it) }
    }

    @Test
    fun `every listed special-purpose range is blocked from its first to its last address`() {
        WebhookTarget.NOT_PUBLIC.forEach { range ->
            val (first, last) = range.bounds()
            assertNotPublic(first.hostAddress)
            assertNotPublic(last.hostAddress)
        }
    }

    @Test
    fun `each special-purpose range blocks a sample address, so dropping a range fails here`() {
        listOf(
            "0.1.2.3", "100.64.0.1", "192.0.0.9", "192.88.99.1", "192.0.2.1", "198.51.100.1", "203.0.113.1", "198.18.0.1",
            "240.0.0.1", "255.255.255.255",
            "::a00:1", // IPv4-compatible 10.0.0.1
            "::ffff:0:7f00:1", // IPv4-translated 127.0.0.1
            "64:ff9b::a00:1", "64:ff9b:1::1", // NAT64 to an internal IPv4 address
            "100::1", "100:0:0:1::1", "2001::1", "2001:db8::1", "3fff::1", "2002:a00:1::", "5f00::1", "fd00::1", "fc00::1",
        ).forEach { assertNotPublic(it) }
    }

    @Test
    fun `addresses just outside the blocked ranges are allowed`() {
        listOf(
            "9.255.255.255", "11.0.0.0", "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0",
            "100.63.255.255", "100.128.0.0", "198.17.255.255", "198.20.0.0", "223.255.255.255",
            "2001:200::", "2001:db7:ffff::1", "2001:db9::", "2001:4860:4860::8888", "2003::1", "2a00:1450::1",
        ).forEach { assertAllowed(it) }
    }

    @Test
    fun `an IPv6 address carrying a mapped IPv4 address is judged as that IPv4 address`() {
        // InetAddress.getByName turns "::ffff:a.b.c.d" text into IPv4; a resolver can still hand back this form.
        val mappedLoopback = Inet6Address.getByAddress(null, ByteArray(10) + byteArrayOf(-1, -1, 127, 0, 0, 1), -1)
        val mappedPublic = Inet6Address.getByAddress(null, ByteArray(10) + byteArrayOf(-1, -1, 8, 8, 8, 8), -1)

        assertBlocked(url) { listOf(mappedLoopback) }
        assertIs<WebhookTarget.Allowed>(WebhookTarget.check(url) { listOf(mappedPublic) })
    }

    @Test
    fun `a host with one internal record among public ones is blocked`() {
        // DNS rebinding or a careless zone: one internal answer is enough to block.
        assertNotPublic("8.8.8.8", "10.0.0.5")
    }

    @Test
    fun `IP literals are refused, including numeric forms parsers disagree on`() {
        // Java reads 0177.0.0.1 as the public 177.0.0.1; curl and many proxies read it as 127.0.0.1.
        listOf(
            "https://8.8.8.8/", "https://[2001:4860:4860::8888]/", "https://127.0.0.1/", "https://[::1]/",
            "https://2130706433/", "https://0x7f000001/", "https://127.1/", "https://0177.0.0.1/",
        ).forEach { assertBlocked(it, dns("8.8.8.8")) }
    }

    @Test
    fun `single-label, oversized and malformed host names are refused without failing`() {
        listOf(
            "https://vault/", "https://localhost/",
            "https://" + "a".repeat(64) + ".example/",
            "https://" + "a.".repeat(5000) + "example/",
            "https://-hooks.partner.example/",
        ).forEach { assertBlocked(it, dns("8.8.8.8")) }
        assertAllowed("8.8.8.8") // the same resolver allows a normal name
    }

    @Test
    fun `the host is resolved as an absolute name, so no search domain is added`() {
        val asked = mutableListOf<String>()
        val resolve = { name: String -> asked += name; listOf(InetAddress.getByName("8.8.8.8")) }

        WebhookTarget.check("https://hooks.partner.example/", resolve)
        WebhookTarget.check("https://hooks.partner.example./", resolve)

        assertEquals(listOf("hooks.partner.example.", "hooks.partner.example."), asked)
    }

    @Test
    fun `a 253-character name is allowed with or without its trailing dot`() {
        val name = listOf("a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(53), "example").joinToString(".")
        assertEquals(253, name.length)
        listOf("https://$name/", "https://$name./")
            .forEach { assertIs<WebhookTarget.Allowed>(WebhookTarget.check(it, dns("8.8.8.8")), it) }
        assertBlocked("https://x.$name/", dns("8.8.8.8")) // 255 characters
    }

    @Test
    fun `only https without credentials is allowed`() {
        listOf(
            "http://hooks.partner.example/", "ftp://hooks.partner.example/", "file:///etc/passwd",
            "https://user:secret@hooks.partner.example/", "https:///no-host", "not a url", "",
        ).forEach { assertBlocked(it, dns("8.8.8.8")) }
    }

    @Test
    fun `a host that does not resolve is reported apart from a blocked one, so delivery can retry`() {
        assertIs<WebhookTarget.Unresolved>(WebhookTarget.check(url) { throw UnknownHostException(it) })
        assertIs<WebhookTarget.Unresolved>(WebhookTarget.check(url) { emptyList() })
    }
}
