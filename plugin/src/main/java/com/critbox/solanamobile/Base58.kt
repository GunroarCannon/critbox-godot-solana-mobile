package com.critbox.solanamobile

import java.math.BigInteger

/** Bitcoin-alphabet Base58, for transaction signatures (64 bytes) and keys. */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE = BigInteger.valueOf(58)

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        var n = BigInteger(1, bytes)
        val sb = StringBuilder()
        while (n.signum() > 0) {
            val (q, r) = n.divideAndRemainder(BASE)
            sb.append(ALPHABET[r.toInt()])
            n = q
        }
        for (b in bytes) {
            if (b.toInt() != 0) break
            sb.append('1')
        }
        return sb.reverse().toString()
    }
}
