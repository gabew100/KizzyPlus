package dev.kizzyplus.companion.discord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Builds files the way MMKV's doAppendDataWithKey writes them (Core/MMKV_IO.cpp). */
class KizzyImportTest {

    private fun varint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }

    /** key: varint length + bytes. value (string, isDataHolder): varint total + varint length + bytes. */
    private fun record(out: ByteArrayOutputStream, key: String, value: String?) {
        val k = key.toByteArray()
        varint(out, k.size)
        out.write(k)
        if (value == null) {
            varint(out, 0)
            return
        }
        val inner = ByteArrayOutputStream()
        val v = value.toByteArray()
        varint(inner, v.size)
        inner.write(v)
        varint(out, inner.size())
        out.write(inner.toByteArray())
    }

    private fun mmkvFile(build: (ByteArrayOutputStream) -> Unit): ByteArray {
        val body = ByteArrayOutputStream()
        varint(body, 0x2A0000) // randomItemSizeHolder(4): a 4-byte varint placeholder
        build(body)
        val size = body.size()
        val file = ByteArrayOutputStream()
        file.write(byteArrayOf(size.toByte(), (size shr 8).toByte(), (size shr 16).toByte(), (size shr 24).toByte()))
        file.write(body.toByteArray())
        // MMKV files are page-sized and zero-padded past the actual size.
        file.write(ByteArray(4096 - file.size()))
        return file.toByteArray()
    }

    @Test
    fun latestRecordWinsAndDeletionsRemoveKeys() {
        val bytes = mmkvFile {
            record(it, "token", "old.token.value")
            record(it, "user", "{\"username\":\"someone\"}")
            record(it, "token", "fake-token-for-tests-newest")
            record(it, "user", null)
        }
        val map = KizzyImport.parseMmkv(bytes)
        assertEquals("fake-token-for-tests-newest", map["token"])
        assertFalse(map.containsKey("user"))
    }

    @Test
    fun longValuesUseMultiByteLengths() {
        val long = "x".repeat(300)
        val map = KizzyImport.parseMmkv(mmkvFile { record(it, "saved_artwork", long) })
        assertEquals(long, map["saved_artwork"])
    }
}
