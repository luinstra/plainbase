package com.plainbase.frameworks.ktor

import java.net.InetAddress
import java.net.UnknownHostException
import java.net.spi.InetAddressResolver
import java.net.spi.InetAddressResolver.LookupPolicy
import java.net.spi.InetAddressResolverProvider
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

internal data class RemoteAddressNoDnsCase(
    val id: String,
    val input: String,
    val expected: String,
    val auxiliary: String? = null,
)

internal data class RemoteAddressNoDnsRow(val id: String, val verdict: String, val attempts: Int)

internal object RemoteAddressNoDnsProtocol {
    const val ROW_PREFIX = "PLAINBASE_NO_DNS_ROW"
    const val COMPLETE_PREFIX = "PLAINBASE_NO_DNS_COMPLETE"
    private const val SEPARATOR = "\t"

    fun row(row: RemoteAddressNoDnsRow): String =
        listOf(ROW_PREFIX, row.id, row.verdict, row.attempts).joinToString(SEPARATOR)

    fun completion(lane: String, count: Int): String =
        listOf(COMPLETE_PREFIX, lane, count).joinToString(SEPARATOR)

    fun parseRow(line: String): RemoteAddressNoDnsRow? {
        val fields = line.split(SEPARATOR)
        if (fields.size != 4 || fields[0] != ROW_PREFIX) return null
        return fields[3].toIntOrNull()?.let { RemoteAddressNoDnsRow(fields[1], fields[2], it) }
    }

    fun parseCompletion(line: String): Pair<String, Int>? {
        val fields = line.split(SEPARATOR)
        if (fields.size != 3 || fields[0] != COMPLETE_PREFIX) return null
        return fields[2].toIntOrNull()?.let { fields[1] to it }
    }
}

internal object RemoteAddressNoDnsCases {
    val lanes: List<String> = listOf("bind", "remote", "remote-cidr", "network-cidr", "parse-cidr", "config")

    private val bind = listOf(
        RemoteAddressNoDnsCase("B-01", "127.0.0.1", "false"),
        RemoteAddressNoDnsCase("B-02", "127.255.255.255", "false"),
        RemoteAddressNoDnsCase("B-03", "::1", "false"),
        RemoteAddressNoDnsCase("B-04", "::ffff:127.0.0.1", "false"),
        RemoteAddressNoDnsCase("B-05", "[::1]:8080", "false"),
        RemoteAddressNoDnsCase("B-06", "localhost", "false"),
        RemoteAddressNoDnsCase("B-07", "LOCALHOST:8080", "false"),
        RemoteAddressNoDnsCase("B-08", "ip6-localhost", "false"),
        RemoteAddressNoDnsCase("B-09", "0.0.0.0", "true"),
        RemoteAddressNoDnsCase("B-10", "::", "true"),
        RemoteAddressNoDnsCase("B-11", "10.0.0.5", "true"),
        RemoteAddressNoDnsCase("B-12", "2001:db8::1", "true"),
        RemoteAddressNoDnsCase("B-13", "example.com", "true"),
        RemoteAddressNoDnsCase("B-14", "dead.beef", "true"),
        RemoteAddressNoDnsCase("B-15", "١٢٧.0.0.1", "true"),
        RemoteAddressNoDnsCase("B-16", "[localhost]", "true"),
        RemoteAddressNoDnsCase("B-17", "127.0.0.1:abc", "true"),
        RemoteAddressNoDnsCase("B-18", "[127.0.0.1%:zone]", "true"),
        RemoteAddressNoDnsCase("B-19", ".::1", "true"),
        RemoteAddressNoDnsCase("B-20", ":::1", "true"),
        RemoteAddressNoDnsCase("B-21", "::ffff:127.1", "true"),
        RemoteAddressNoDnsCase("B-22", "[::ffff:١٢٧.0.0.1]", "true"),
        RemoteAddressNoDnsCase("B-23", "127.0.1", "true"),
        RemoteAddressNoDnsCase("B-24", "127..0.1", "true"),
        RemoteAddressNoDnsCase("B-25", "2130706433", "true"),
        RemoteAddressNoDnsCase("B-26", "127.0.0.1%lo0", "true"),
        RemoteAddressNoDnsCase("B-27", "[ip6-localhost]:8080", "true"),
        RemoteAddressNoDnsCase("B-28", "[::ffff:127.0.0.1]:8080", "false"),
        RemoteAddressNoDnsCase("B-29", "256.0.0.1", "true"),
        RemoteAddressNoDnsCase("B-30", "127.+0.0.1", "true"),
        RemoteAddressNoDnsCase("B-31", "127.-0.0.1", "true"),
        RemoteAddressNoDnsCase("B-32", "010.0.0.1", "true"),
        RemoteAddressNoDnsCase("B-33", "127.000.0.1", "true"),
        RemoteAddressNoDnsCase("B-34", "127.1", "true"),
        RemoteAddressNoDnsCase("B-35", "IP6-LOCALHOST", "false"),
        RemoteAddressNoDnsCase("B-36", "ip6-localhost:8080", "false"),
    )

    private val remote = listOf(
        RemoteAddressNoDnsCase("R-01", "127.0.0.1", "true"),
        RemoteAddressNoDnsCase("R-02", "127.255.255.255", "true"),
        RemoteAddressNoDnsCase("R-03", "::1", "true"),
        RemoteAddressNoDnsCase("R-04", "::ffff:127.0.0.1", "true"),
        RemoteAddressNoDnsCase("R-05", "127.0.0.1:0", "true"),
        RemoteAddressNoDnsCase("R-06", "[::1]:65535", "true"),
        RemoteAddressNoDnsCase("R-07", "localhost", "true"),
        RemoteAddressNoDnsCase("R-08", "LOCALHOST:8080", "true"),
        RemoteAddressNoDnsCase("R-09", "ip6-localhost", "true"),
        RemoteAddressNoDnsCase("R-10", "::1%lo0", "true"),
        RemoteAddressNoDnsCase("R-11", "10.0.0.5", "false"),
        RemoteAddressNoDnsCase("R-12", "::", "false"),
        RemoteAddressNoDnsCase("R-13", "fe80::1%eth0", "false"),
        RemoteAddressNoDnsCase("R-14", "example.com", "false"),
        RemoteAddressNoDnsCase("R-15", "localhost.example.com", "false"),
        RemoteAddressNoDnsCase("R-16", "dead.beef", "false"),
        RemoteAddressNoDnsCase("R-17", "cafe.babe", "false"),
        RemoteAddressNoDnsCase("R-18", "face.feed", "false"),
        RemoteAddressNoDnsCase("R-19", "127.1", "false"),
        RemoteAddressNoDnsCase("R-20", "010.0.0.1", "false"),
        RemoteAddressNoDnsCase("R-21", "127.000.0.1", "false"),
        RemoteAddressNoDnsCase("R-22", "127.+0.0.1", "false"),
        RemoteAddressNoDnsCase("R-23", "١٢٧.0.0.1", "false"),
        RemoteAddressNoDnsCase("R-24", "127.0.0.1:abc", "false"),
        RemoteAddressNoDnsCase("R-25", "[::1]:٨٠", "false"),
        RemoteAddressNoDnsCase("R-26", "[::1]junk", "false"),
        RemoteAddressNoDnsCase("R-27", "[::1", "false"),
        RemoteAddressNoDnsCase("R-28", "[localhost]", "false"),
        RemoteAddressNoDnsCase("R-29", "[127.0.0.1]", "false"),
        RemoteAddressNoDnsCase("R-30", "[127.0.0.1%:zone]", "false"),
        RemoteAddressNoDnsCase("R-31", ".::1", "false"),
        RemoteAddressNoDnsCase("R-32", ":::1", "false"),
        RemoteAddressNoDnsCase("R-33", "::ffff:127.1", "false"),
        RemoteAddressNoDnsCase("R-34", "[::ffff:١٢٧.0.0.1]", "false"),
        RemoteAddressNoDnsCase("R-35", "127.0.1", "false"),
        RemoteAddressNoDnsCase("R-36", "127..0.1", "false"),
        RemoteAddressNoDnsCase("R-37", "2130706433", "false"),
        RemoteAddressNoDnsCase("R-38", "127.0.0.1%lo0", "false"),
        RemoteAddressNoDnsCase("R-39", "[ip6-localhost]:8080", "false"),
        RemoteAddressNoDnsCase("R-40", "[::ffff:127.0.0.1]:8080", "true"),
        RemoteAddressNoDnsCase("R-41", "127.0.0.1:8080", "true"),
        RemoteAddressNoDnsCase("R-42", "127.0.0.1:65535", "true"),
        RemoteAddressNoDnsCase("R-43", "[::1]", "true"),
        RemoteAddressNoDnsCase("R-44", "[::1]:0", "true"),
        RemoteAddressNoDnsCase("R-45", "[::1]:8080", "true"),
        RemoteAddressNoDnsCase("R-46", "::1:8080", "false"),
        RemoteAddressNoDnsCase("R-47", "127.0.0.1:", "false"),
        RemoteAddressNoDnsCase("R-48", "[::1]:", "false"),
        RemoteAddressNoDnsCase("R-49", "127.0.0.1:8 0", "false"),
        RemoteAddressNoDnsCase("R-50", "127.0.0.1:65536", "false"),
        RemoteAddressNoDnsCase("R-51", "127.0.0.1:999999999999999999999999", "false"),
        RemoteAddressNoDnsCase("R-52", "[::1]:+80", "false"),
        RemoteAddressNoDnsCase("R-53", "[::1]:-1", "false"),
        RemoteAddressNoDnsCase("R-54", "[::1]:8 0", "false"),
        RemoteAddressNoDnsCase("R-55", "[::1]:65536", "false"),
        RemoteAddressNoDnsCase("R-56", "[::1]:999999999999999999999999", "false"),
        RemoteAddressNoDnsCase("R-57", "[[::1]]", "false"),
        RemoteAddressNoDnsCase("R-58", "[::1]]", "false"),
        RemoteAddressNoDnsCase("R-59", "127.0.0.1:٨٠", "false"),
        RemoteAddressNoDnsCase("R-60", "127.0.0.1:+80", "false"),
        RemoteAddressNoDnsCase("R-61", "127.0.0.1:-1", "false"),
        RemoteAddressNoDnsCase("R-62", "[::1]:abc", "false"),
        RemoteAddressNoDnsCase("R-63", " 127.0.0.1:8080 ", "true"),
        RemoteAddressNoDnsCase("R-64", " [::1]:8080 ", "true"),
        RemoteAddressNoDnsCase("R-65", "256.0.0.1", "false"),
        RemoteAddressNoDnsCase("R-66", "::ffff:127.000.0.1", "false"),
        RemoteAddressNoDnsCase("R-67", "::ffff:010.0.0.1", "false"),
        RemoteAddressNoDnsCase("R-68", "IP6-LOCALHOST", "true"),
        RemoteAddressNoDnsCase("R-69", "ip6-localhost:8080", "true"),
    )

    private val remoteCidr = listOf(
        RemoteAddressNoDnsCase("M-01", "10.0.0.5", "true", "10.0.0.0/8"),
        RemoteAddressNoDnsCase("M-02", "10.0.1.5", "false", "10.0.0.0/24"),
        RemoteAddressNoDnsCase("M-03", "2001:db8:0:1::5", "true", "2001:db8:0:1::/64"),
        RemoteAddressNoDnsCase("M-04", "fe80::1%eth0", "true", "fe80::/16"),
        RemoteAddressNoDnsCase("M-05", "::ffff:127.0.0.1", "true", "127.0.0.0/8"),
        RemoteAddressNoDnsCase("M-06", "10.0.0.5", "false", "2001:db8::/32"),
        RemoteAddressNoDnsCase("M-07", "dead.beef", "false", "127.0.0.0/8"),
        RemoteAddressNoDnsCase("M-08", "cafe.babe", "false", "127.0.0.0/8"),
        RemoteAddressNoDnsCase("M-09", "127.1", "false", "127.0.0.0/8"),
        RemoteAddressNoDnsCase("M-10", "::ffff:010.0.0.1", "false", "10.0.0.0/8"),
        RemoteAddressNoDnsCase("M-11", "١٢٧.0.0.1", "false", "127.0.0.0/8"),
        RemoteAddressNoDnsCase("M-12", "10.0.0.5", "false", "10.0.0.0/33"),
        RemoteAddressNoDnsCase("M-13", "[127.0.0.1%:zone]", "false", "127.0.0.0/8"),
        RemoteAddressNoDnsCase("M-14", ".::1", "false", "::/0"),
        RemoteAddressNoDnsCase("M-15", ":::1", "false", "::/0"),
        RemoteAddressNoDnsCase("M-16", "::ffff:127.1", "false", "127.0.0.0/8"),
        RemoteAddressNoDnsCase("M-17", "[::ffff:١٢٧.0.0.1]", "false", "127.0.0.0/8"),
        RemoteAddressNoDnsCase("M-18", "[::ffff:127.0.0.1]:8080", "true", "127.0.0.0/8"),
        RemoteAddressNoDnsCase("M-19", "127.0.1", "false", "127.0.0.0/8"),
        RemoteAddressNoDnsCase("M-20", "127..0.1", "false", "127.0.0.0/8"),
        RemoteAddressNoDnsCase("M-21", "2130706433", "false", "127.0.0.0/8"),
        RemoteAddressNoDnsCase("M-22", "127.0.0.1%lo0", "false", "127.0.0.0/8"),
        RemoteAddressNoDnsCase("M-23", "::ffff:127.000.0.1", "false", "127.0.0.0/8"),
        RemoteAddressNoDnsCase("M-24", "127.0.0.1", "true", "0.0.0.0/0"),
        RemoteAddressNoDnsCase("M-25", "127.0.0.1", "true", "127.0.0.0/24"),
        RemoteAddressNoDnsCase("M-26", "127.0.0.1", "true", "127.0.0.1/32"),
        RemoteAddressNoDnsCase("M-27", "127.0.0.2", "false", "127.0.0.1/32"),
        RemoteAddressNoDnsCase("M-28", "127.0.0.1", "false", "::/0"),
        RemoteAddressNoDnsCase("M-29", "::1", "true", "::/0"),
        RemoteAddressNoDnsCase("M-30", "::1", "true", "::1/128"),
        RemoteAddressNoDnsCase("M-31", "::2", "false", "::1/128"),
        RemoteAddressNoDnsCase("M-32", "::1", "false", "::/129"),
        RemoteAddressNoDnsCase("M-33", "256.0.0.1", "false", "127.0.0.0/8"),
        RemoteAddressNoDnsCase("M-34", "::ffff:127.0.0.0", "true", "::ffff:127.0.0.0/32"),
        RemoteAddressNoDnsCase("M-35", "127.+0.0.1", "false", "127.0.0.0/8"),
        RemoteAddressNoDnsCase("M-36", "127.-0.0.1", "false", "127.0.0.0/8"),
        RemoteAddressNoDnsCase("M-37", "127.000.0.1", "false", "127.0.0.0/8"),
        RemoteAddressNoDnsCase("M-38", "::ffff:127.0.0.0", "false", "::ffff:127.0.0.0/33"),
    )

    private val networkCidr = listOf(
        RemoteAddressNoDnsCase("N-01", "10.0.0.0/8", "true", "10.0.0.5"),
        RemoteAddressNoDnsCase("N-02", "2001:db8::/32", "true", "2001:db8::1"),
        RemoteAddressNoDnsCase("N-03", "fe80::/16", "true", "fe80::1%eth0"),
        RemoteAddressNoDnsCase("N-04", "127.0.0.0/8", "true", "::ffff:127.0.0.1"),
        RemoteAddressNoDnsCase("N-05", "dead.beef/8", "false", "127.0.0.1"),
        RemoteAddressNoDnsCase("N-06", "cafe.babe/8", "false", "127.0.0.1"),
        RemoteAddressNoDnsCase("N-07", "127.1/8", "false", "127.0.0.1"),
        RemoteAddressNoDnsCase("N-08", "010.0.0.0/8", "false", "10.0.0.5"),
        RemoteAddressNoDnsCase("N-09", "١٢٧.0.0.0/8", "false", "127.0.0.1"),
        RemoteAddressNoDnsCase("N-10", "localhost/8", "false", "127.0.0.1"),
        RemoteAddressNoDnsCase("N-11", "10.0.0.0/33", "false", "10.0.0.5"),
        RemoteAddressNoDnsCase("N-12", "::ffff:127.1/8", "false", "127.0.0.1"),
        RemoteAddressNoDnsCase("N-13", ".::1/128", "false", "127.0.0.1"),
        RemoteAddressNoDnsCase("N-14", ":::1/128", "false", "127.0.0.1"),
        RemoteAddressNoDnsCase("N-15", "[::ffff:١٢٧.0.0.1]/128", "false", "127.0.0.1"),
        RemoteAddressNoDnsCase("N-16", "[127.0.0.1%:zone]/8", "false", "127.0.0.1"),
        RemoteAddressNoDnsCase("N-17", "0.0.0.0/0", "true", "10.0.0.5"),
        RemoteAddressNoDnsCase("N-18", "127.0.0.0/24", "true", "127.0.0.1"),
        RemoteAddressNoDnsCase("N-19", "127.0.0.1/32", "true", "127.0.0.1"),
        RemoteAddressNoDnsCase("N-20", "::/0", "true", "::1"),
        RemoteAddressNoDnsCase("N-21", "2001:db8::/64", "true", "2001:db8::1"),
        RemoteAddressNoDnsCase("N-22", "2001:db8::1/128", "true", "2001:db8::1"),
        RemoteAddressNoDnsCase("N-23", "2001:db8::/129", "false", "2001:db8::1"),
        RemoteAddressNoDnsCase("N-24", "127.0.0.0%lo0/8", "false", "127.0.0.1"),
        RemoteAddressNoDnsCase("N-25", "127.0.1/8", "false", "127.0.0.1"),
        RemoteAddressNoDnsCase("N-26", "127..0.1/8", "false", "127.0.0.1"),
        RemoteAddressNoDnsCase("N-27", "2130706433/8", "false", "127.0.0.1"),
        RemoteAddressNoDnsCase("N-28", "::ffff:127.000.0.1/8", "false", "127.0.0.1"),
        RemoteAddressNoDnsCase("N-29", "[::ffff:127.0.0.1]/8", "false", "127.0.0.1"),
        RemoteAddressNoDnsCase("N-30", "256.0.0.1/8", "false", "127.0.0.1"),
        RemoteAddressNoDnsCase("N-31", "127.+0.0.1/8", "false", "127.0.0.1"),
        RemoteAddressNoDnsCase("N-32", "127.-0.0.1/8", "false", "127.0.0.1"),
        RemoteAddressNoDnsCase("N-33", "127.000.0.1/8", "false", "127.0.0.1"),
        RemoteAddressNoDnsCase("N-34", "::ffff:010.0.0.0/8", "false", "127.0.0.1"),
        RemoteAddressNoDnsCase("N-35", "::ffff:127.0.0.0/32", "true", "::ffff:127.0.0.0"),
        RemoteAddressNoDnsCase("N-36", "::ffff:127.0.0.0/33", "false", "::ffff:127.0.0.0"),
        RemoteAddressNoDnsCase("N-37", "::ffff:10.0.0.0/8", "true", "10.0.0.5"),
        RemoteAddressNoDnsCase("N-38", "::ffff:10.0.0.0/33", "false", "10.0.0.5"),
        RemoteAddressNoDnsCase("N-39", "ip6-localhost/128", "false", "127.0.0.1"),
    )

    private val parseCidr = listOf(
        RemoteAddressNoDnsCase("P-01", "10.0.0.0/8", "true"),
        RemoteAddressNoDnsCase("P-02", "2001:db8::/32", "true"),
        RemoteAddressNoDnsCase("P-03", "::/0", "true"),
        RemoteAddressNoDnsCase("P-04", "10.0.0.0/33", "false"),
        RemoteAddressNoDnsCase("P-05", "10.0/8", "false"),
        RemoteAddressNoDnsCase("P-06", "010.0.0.0/8", "false"),
        RemoteAddressNoDnsCase("P-07", "dead.beef/8", "false"),
        RemoteAddressNoDnsCase("P-08", "cafe.babe/8", "false"),
        RemoteAddressNoDnsCase("P-09", "١٢٧.0.0.0/8", "false"),
        RemoteAddressNoDnsCase("P-10", "localhost/8", "false"),
        RemoteAddressNoDnsCase("P-11", "::ffff:010.0.0.0/8", "false"),
        RemoteAddressNoDnsCase("P-12", "127.0.0.0", "false"),
        RemoteAddressNoDnsCase("P-13", "::ffff:127.1/8", "false"),
        RemoteAddressNoDnsCase("P-14", ".::1/128", "false"),
        RemoteAddressNoDnsCase("P-15", ":::1/128", "false"),
        RemoteAddressNoDnsCase("P-16", "[::ffff:١٢٧.0.0.1]/128", "false"),
        RemoteAddressNoDnsCase("P-17", "[127.0.0.1%:zone]/8", "false"),
        RemoteAddressNoDnsCase("P-18", "0.0.0.0/0", "true"),
        RemoteAddressNoDnsCase("P-19", "127.0.0.0/24", "true"),
        RemoteAddressNoDnsCase("P-20", "127.0.0.1/32", "true"),
        RemoteAddressNoDnsCase("P-21", "2001:db8::/64", "true"),
        RemoteAddressNoDnsCase("P-22", "2001:db8::1/128", "true"),
        RemoteAddressNoDnsCase("P-23", "2001:db8::/129", "false"),
        RemoteAddressNoDnsCase("P-24", "127.0.0.0%lo0/8", "false"),
        RemoteAddressNoDnsCase("P-25", "127.0.1/8", "false"),
        RemoteAddressNoDnsCase("P-26", "127..0.1/8", "false"),
        RemoteAddressNoDnsCase("P-27", "2130706433/8", "false"),
        RemoteAddressNoDnsCase("P-28", "::ffff:127.000.0.1/8", "false"),
        RemoteAddressNoDnsCase("P-29", "[::ffff:127.0.0.1]/8", "false"),
        RemoteAddressNoDnsCase("P-30", "256.0.0.1/8", "false"),
        RemoteAddressNoDnsCase("P-31", "127.+0.0.1/8", "false"),
        RemoteAddressNoDnsCase("P-32", "127.-0.0.1/8", "false"),
        RemoteAddressNoDnsCase("P-33", "127.000.0.1/8", "false"),
        RemoteAddressNoDnsCase("P-34", "::ffff:127.0.0.0/32", "true"),
        RemoteAddressNoDnsCase("P-35", "::ffff:127.0.0.0/33", "false"),
        RemoteAddressNoDnsCase("P-36", "::ffff:10.0.0.0/8", "true"),
        RemoteAddressNoDnsCase("P-37", "::ffff:10.0.0.0/33", "false"),
        RemoteAddressNoDnsCase("P-38", "ip6-localhost/128", "false"),
    )

    private val config = listOf(
        RemoteAddressNoDnsCase("C-01", "10.0.0.0/8", "loaded"),
        RemoteAddressNoDnsCase("C-02", "::/0", "loaded"),
        RemoteAddressNoDnsCase("C-03", "dead.beef/8", "iae:PLAINBASE_TRUSTED_PROXY"),
        RemoteAddressNoDnsCase("C-04", "cafe.babe/8", "iae:PLAINBASE_TRUSTED_PROXY"),
        RemoteAddressNoDnsCase("C-05", "127.1/8", "iae:PLAINBASE_TRUSTED_PROXY"),
        RemoteAddressNoDnsCase("C-06", "١٢٧.0.0.0/8", "iae:PLAINBASE_TRUSTED_PROXY"),
        RemoteAddressNoDnsCase("C-07", "[::1]/128", "iae:PLAINBASE_TRUSTED_PROXY"),
        RemoteAddressNoDnsCase("C-08", "127.0.0.0", "iae:PLAINBASE_TRUSTED_PROXY"),
        RemoteAddressNoDnsCase("C-09", "127.0.0.0%lo0/8", "iae:PLAINBASE_TRUSTED_PROXY"),
        RemoteAddressNoDnsCase("C-10", "127.0.1/8", "iae:PLAINBASE_TRUSTED_PROXY"),
        RemoteAddressNoDnsCase("C-11", "127..0.1/8", "iae:PLAINBASE_TRUSTED_PROXY"),
        RemoteAddressNoDnsCase("C-12", "2130706433/8", "iae:PLAINBASE_TRUSTED_PROXY"),
        RemoteAddressNoDnsCase("C-13", "::ffff:127.000.0.1/8", "iae:PLAINBASE_TRUSTED_PROXY"),
        RemoteAddressNoDnsCase("C-14", "[::ffff:127.0.0.1]/8", "iae:PLAINBASE_TRUSTED_PROXY"),
        RemoteAddressNoDnsCase("C-15", "256.0.0.1/8", "iae:PLAINBASE_TRUSTED_PROXY"),
        RemoteAddressNoDnsCase("C-16", "127.+0.0.1/8", "iae:PLAINBASE_TRUSTED_PROXY"),
        RemoteAddressNoDnsCase("C-17", "127.-0.0.1/8", "iae:PLAINBASE_TRUSTED_PROXY"),
        RemoteAddressNoDnsCase("C-18", "127.000.0.1/8", "iae:PLAINBASE_TRUSTED_PROXY"),
        RemoteAddressNoDnsCase("C-19", "010.0.0.0/8", "iae:PLAINBASE_TRUSTED_PROXY"),
        RemoteAddressNoDnsCase("C-20", "::ffff:010.0.0.0/8", "iae:PLAINBASE_TRUSTED_PROXY"),
        RemoteAddressNoDnsCase("C-21", "::ffff:127.0.0.0/32", "loaded"),
        RemoteAddressNoDnsCase("C-22", "::ffff:127.0.0.0/33", "iae:PLAINBASE_TRUSTED_PROXY"),
    )

    fun cases(lane: String): List<RemoteAddressNoDnsCase> = when (lane) {
        "bind" -> bind
        "remote" -> remote
        "remote-cidr" -> remoteCidr
        "network-cidr" -> networkCidr
        "parse-cidr" -> parseCidr
        "config" -> config
        else -> error("unknown lane: $lane")
    }
}

private const val CONTROL_HOST = "control.invalid"
private val LOOPBACK_BYTES = byteArrayOf(127, 0, 0, 1)
private val FIXTURE_HOSTS = setOf("dead.beef", "cafe.babe", "face.feed")

internal object RemoteAddressResolverAttempts {
    private val count = AtomicInteger()

    fun reset() = count.set(0)

    fun get(): Int = count.get()

    fun increment() = count.incrementAndGet()
}

class RemoteAddressResolverProvider : InetAddressResolverProvider() {
    override fun get(configuration: Configuration): InetAddressResolver = object : InetAddressResolver {
        override fun lookupByName(host: String, lookupPolicy: LookupPolicy): Stream<InetAddress> {
            RemoteAddressResolverAttempts.increment()
            if (host == CONTROL_HOST || host in FIXTURE_HOSTS) {
                return Stream.of(InetAddress.getByAddress(host, LOOPBACK_BYTES))
            }
            throw UnknownHostException(host)
        }

        override fun lookupByAddress(addr: ByteArray): String {
            RemoteAddressResolverAttempts.increment()
            if (addr.contentEquals(LOOPBACK_BYTES)) return CONTROL_HOST
            throw UnknownHostException("reverse lookup rejected")
        }
    }

    override fun name(): String = "plainbase-no-dns-test"
}
