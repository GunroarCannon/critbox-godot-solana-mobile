package com.critbox.solanamobile

import com.solana.publickey.ProgramDerivedAddress
import com.solana.publickey.SolanaPublicKey
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream

/**
 * Builds unsigned legacy transactions for the transfers a game needs: SOL, or an
 * SPL / Token-2022 token (like SKR) with an optional memo and an idempotent
 * create of the receiver's associated token account.
 *
 * Messages are compiled here rather than through web3-solana's builder so the
 * fee payer is always account 0, writable and signing, whatever the instruction order.
 */
object TxBuilder {
    const val SYSTEM_PROGRAM = "11111111111111111111111111111111"
    const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    const val TOKEN_2022_PROGRAM = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    const val ATA_PROGRAM = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
    const val MEMO_PROGRAM = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"

    /** One transfer. [mint] empty = SOL in lamports; otherwise token base units. */
    data class Transfer(
        val payer: String,
        val to: String,
        val amount: ULong,
        val blockhash: String,
        val mint: String = "",
        val decimals: Int = 0,
        val tokenProgram: String = TOKEN_PROGRAM,
        val createAta: Boolean = true,
        val memo: String = "",
    )

    data class Meta(val key: SolanaPublicKey, val signer: Boolean, val writable: Boolean)
    data class Ix(val program: SolanaPublicKey, val accounts: List<Meta>, val data: ByteArray)

    fun key(s: String): SolanaPublicKey = SolanaPublicKey.from(s)

    fun associatedTokenAddress(owner: String, mint: String, tokenProgram: String = TOKEN_PROGRAM): SolanaPublicKey =
        runBlocking {
            ProgramDerivedAddress.find(
                listOf(key(owner).bytes, key(tokenProgram).bytes, key(mint).bytes),
                key(ATA_PROGRAM),
            ).getOrThrow()
        }.let { SolanaPublicKey(it.bytes) }

    /** The unsigned, serialized transaction (zeroed signature slots). */
    fun build(t: Transfer): ByteArray = serializeTransaction(instructions(t), key(t.payer), key(t.blockhash))

    fun instructions(t: Transfer): List<Ix> {
        require(t.amount > 0uL) { "amount must be above zero" }
        val payer = key(t.payer)
        val to = key(t.to)
        val out = mutableListOf<Ix>()
        if (t.mint.isEmpty()) {
            out += Ix(key(SYSTEM_PROGRAM), listOf(Meta(payer, true, true), Meta(to, false, true)),
                le32(2) + le64(t.amount))
        } else {
            require(t.decimals in 0..255) { "decimals out of range" }
            val mint = key(t.mint)
            val program = key(t.tokenProgram)
            val source = associatedTokenAddress(t.payer, t.mint, t.tokenProgram)
            val dest = associatedTokenAddress(t.to, t.mint, t.tokenProgram)
            if (t.createAta) {
                // Associated Token Account: CreateIdempotent (instruction 1).
                out += Ix(key(ATA_PROGRAM), listOf(
                    Meta(payer, true, true),
                    Meta(dest, false, true),
                    Meta(to, false, false),
                    Meta(mint, false, false),
                    Meta(key(SYSTEM_PROGRAM), false, false),
                    Meta(program, false, false),
                ), byteArrayOf(1))
            }
            // Token: TransferChecked (instruction 12) = amount u64 LE + decimals u8.
            out += Ix(program, listOf(
                Meta(source, false, true),
                Meta(mint, false, false),
                Meta(dest, false, true),
                Meta(payer, true, false),
            ), byteArrayOf(12) + le64(t.amount) + byteArrayOf(t.decimals.toByte()))
        }
        if (t.memo.isNotEmpty()) {
            out += Ix(key(MEMO_PROGRAM), listOf(Meta(payer, true, false)), t.memo.toByteArray(Charsets.UTF_8))
        }
        return out
    }

    // --- Legacy message compile ---

    fun serializeTransaction(ixs: List<Ix>, feePayer: SolanaPublicKey, blockhash: SolanaPublicKey): ByteArray {
        val message = compileMessage(ixs, feePayer, blockhash)
        val signers = message[0].toInt()
        val out = ByteArrayOutputStream()
        out.write(compactU16(signers))
        out.write(ByteArray(64 * signers))
        out.write(message)
        return out.toByteArray()
    }

    fun compileMessage(ixs: List<Ix>, feePayer: SolanaPublicKey, blockhash: SolanaPublicKey): ByteArray {
        // Merge every account's flags, in first-seen order, fee payer first.
        val order = LinkedHashMap<String, Meta>()
        fun add(m: Meta) {
            val k = m.key.base58()
            val prev = order[k]
            order[k] = if (prev == null) m else Meta(m.key, prev.signer || m.signer, prev.writable || m.writable)
        }
        add(Meta(feePayer, true, true))
        for (ix in ixs) {
            ix.accounts.forEach(::add)
            add(Meta(ix.program, false, false))
        }
        val metas = order.values.toList()
        val payerMeta = metas.first()
        val rest = metas.drop(1)
        val sorted = listOf(payerMeta) +
            rest.filter { it.signer && it.writable } +
            rest.filter { it.signer && !it.writable } +
            rest.filter { !it.signer && it.writable } +
            rest.filter { !it.signer && !it.writable }
        val index = sorted.withIndex().associate { (i, m) -> m.key.base58() to i }

        val out = ByteArrayOutputStream()
        out.write(sorted.count { it.signer })
        out.write(sorted.count { it.signer && !it.writable })
        out.write(sorted.count { !it.signer && !it.writable })
        out.write(compactU16(sorted.size))
        sorted.forEach { out.write(it.key.bytes) }
        out.write(blockhash.bytes)
        out.write(compactU16(ixs.size))
        for (ix in ixs) {
            out.write(index.getValue(ix.program.base58()))
            out.write(compactU16(ix.accounts.size))
            ix.accounts.forEach { out.write(index.getValue(it.key.base58())) }
            out.write(compactU16(ix.data.size))
            out.write(ix.data)
        }
        return out.toByteArray()
    }

    // --- Encoding helpers ---

    fun compactU16(value: Int): ByteArray {
        require(value in 0..0xffff)
        val out = ByteArrayOutputStream()
        var v = value
        while (true) {
            val low = v and 0x7f
            v = v shr 7
            if (v == 0) {
                out.write(low)
                return out.toByteArray()
            }
            out.write(low or 0x80)
        }
    }

    fun le32(v: Int): ByteArray = ByteArray(4) { i -> (v ushr (8 * i)).toByte() }

    fun le64(v: ULong): ByteArray = ByteArray(8) { i -> (v shr (8 * i)).toByte() }
}
