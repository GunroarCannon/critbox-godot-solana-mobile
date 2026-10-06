package com.critbox.solanamobile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Vectors come from @solana/web3.js 1.x + @solana/spl-token 0.4
 * (payer / to / blockhash = Keypair.fromSeed(fill 7 / 9 / 3)), so these check the
 * Kotlin builder against the reference implementation, not against itself.
 */
class TxBuilderTest {
    private val payer = "GmaDrppBC7P5ARKV8g3djiwP89vz1jLK23V2GBjuAEGB"
    private val to = "J2xccRtuG43drESLYznHhLhQkLTdfepcKYbiQ9BsJVaf"
    private val blockhash = "GyGKxMyg1p9SsHfm15MkNUu1u9TN2JtTspcdmrtGUdse"
    private val skr = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"

    @Test
    fun associatedTokenAddressesMatchSplToken() {
        assertEquals("B6n6eUcbQav2hXthhUBxdaf8DzyR29477JFXjCiMWkur", TxBuilder.associatedTokenAddress(payer, skr).base58())
        assertEquals("4iWxVkeBFEpy1mWmixC4P4iFss52XaKzJB29CXXqhZpJ", TxBuilder.associatedTokenAddress(to, skr).base58())
        assertEquals("DyXvKST7GTdprghFVznrdocdegAsPgT3YwfmTZKxBaba",
            TxBuilder.associatedTokenAddress(to, skr, TxBuilder.TOKEN_2022_PROGRAM).base58())
    }

    @Test
    fun solTransferIsByteIdenticalToWeb3js() {
        val expected = Base64.getDecoder().decode(
            "AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAED6kpsY+KcUgq+9VB7" +
            "Ey7F+ZVHdq6+vnuSQh7qaRRG0iz9FyQ4WqDHW2T7eM1gL6HZkf3r92sTxY7XAurINen2GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAA7UkoxijRwsbq6QM4kFmVYSlZJzpcY/k2NsFGFKyHN9EBAgIAAQwCAAAAh9YSAAAAAAA=")
        val built = TxBuilder.build(TxBuilder.Transfer(payer = payer, to = to, amount = 1234567uL, blockhash = blockhash))
        assertArrayEquals(expected, built)
    }

    @Test
    fun splTransferInstructionsMatchSplToken() {
        val ixs = TxBuilder.instructions(TxBuilder.Transfer(
            payer = payer, to = to, amount = 5_000_000uL, blockhash = blockhash,
            mint = skr, decimals = 6, memo = "order:abc"))
        val expected = listOf(
            Triple("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL", listOf(
                Triple(payer, true, true),
                Triple("4iWxVkeBFEpy1mWmixC4P4iFss52XaKzJB29CXXqhZpJ", false, true),
                Triple(to, false, false),
                Triple(skr, false, false),
                Triple("11111111111111111111111111111111", false, false),
                Triple("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", false, false)), "01"),
            Triple("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", listOf(
                Triple("B6n6eUcbQav2hXthhUBxdaf8DzyR29477JFXjCiMWkur", false, true),
                Triple(skr, false, false),
                Triple("4iWxVkeBFEpy1mWmixC4P4iFss52XaKzJB29CXXqhZpJ", false, true),
                Triple(payer, true, false)), "0c404b4c000000000006"),
            Triple("MemoSq4gqABAXKb96qnH8TzdLX1ki1BvNXMwCuXp6fX", listOf(Triple(payer, true, false)), "6f726465723a616263"),
        )
        assertEquals(expected.size, ixs.size)
        expected.zip(ixs).forEach { (want, got) ->
            assertEquals(want.first, got.program.base58())
            assertEquals(want.second, got.accounts.map { Triple(it.key.base58(), it.signer, it.writable) })
            assertEquals(want.third, got.data.joinToString("") { "%02x".format(it) })
        }
    }

    @Test
    fun splMessageHasFeePayerFirstAndOneSigner() {
        val tx = TxBuilder.build(TxBuilder.Transfer(
            payer = payer, to = to, amount = 5_000_000uL, blockhash = blockhash, mint = skr, decimals = 6, createAta = false))
        assertEquals(1, tx[0].toInt())              // one signature slot
        val msg = tx.copyOfRange(65, tx.size)
        assertEquals(1, msg[0].toInt())             // required signatures
        assertEquals(0, msg[1].toInt())             // read-only signed: payer stays writable
        assertArrayEquals(TxBuilder.key(payer).bytes, msg.copyOfRange(4, 36))
    }

    @Test
    fun compactU16AndBase58() {
        assertArrayEquals(byteArrayOf(0x7f), TxBuilder.compactU16(127))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), TxBuilder.compactU16(128))
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0x03), TxBuilder.compactU16(0xffff))
        assertEquals(payer, Base58.encode(TxBuilder.key(payer).bytes))
        assertEquals("1111", Base58.encode(ByteArray(4)))
        assertTrue(Base58.encode(ByteArray(64) { 1 }).isNotEmpty())
    }
}
