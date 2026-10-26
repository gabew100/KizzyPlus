package dev.kizzyplus.companion.discord

import dev.kizzyplus.companion.root.RootShell

/**
 * Reads the Discord token Kizzy saved when you signed in there. Kizzy keeps it in MMKV
 * (Prefs.kt: MMKV.defaultMMKV(), key "token"), which lands in files/mmkv/mmkv.default.
 */
object KizzyImport {
    const val PACKAGE = "com.my.kizzy"
    private const val MMKV_FILE = "/data/data/$PACKAGE/files/mmkv/mmkv.default"
    private val TOKEN_SHAPE = Regex("[A-Za-z0-9_-]{24,30}\\.[A-Za-z0-9_-]{6}\\.[A-Za-z0-9_-]{27,40}")

    fun isInstalled(): Boolean = RootShell.run("[ -d /data/data/$PACKAGE ]").exitCode == 0

    fun readToken(): String? {
        val bytes = RootShell.readBytes(MMKV_FILE) ?: return null
        return parseMmkv(bytes)["token"]?.takeIf { it.isNotBlank() }
            ?: TOKEN_SHAPE.findAll(String(bytes, Charsets.ISO_8859_1)).lastOrNull()?.value
    }

    /**
     * MMKV file layout (unencrypted): a 4-byte little-endian length, then one varint (the
     * container size, or the 0x00FFFFFF placeholder written before the first append), then
     * key/value records appended over time. Each key is a varint length plus UTF-8; each value
     * is a varint length plus a protobuf-encoded value, which for strings is again a varint
     * length plus UTF-8. Later records override earlier ones; an empty value is a deletion.
     */
    fun parseMmkv(bytes: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        if (bytes.size < 5) return out
        val actual = (bytes[0].toInt() and 0xFF) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or
            ((bytes[3].toInt() and 0xFF) shl 24)
        val end = if (actual in 1..(bytes.size - 4)) 4 + actual else bytes.size
        val reader = Reader(bytes, 4, end)
        reader.varint() ?: return out
        while (reader.hasMore()) {
            val keyLen = reader.varint() ?: break
            val key = reader.bytes(keyLen)?.let { String(it, Charsets.UTF_8) } ?: break
            val valueLen = reader.varint() ?: break
            val value = reader.bytes(valueLen) ?: break
            if (value.isEmpty()) {
                out.remove(key)
                continue
            }
            val inner = Reader(value, 0, value.size)
            val strLen = inner.varint()
            val str = strLen?.let { inner.bytes(it) }
            if (str != null && !inner.hasMore()) out[key] = String(str, Charsets.UTF_8)
        }
        return out
    }

    private class Reader(private val data: ByteArray, private var pos: Int, private val end: Int) {
        fun hasMore() = pos < end

        fun varint(): Int? {
            var result = 0L
            var shift = 0
            while (pos < end && shift < 35) {
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result.toInt()
                shift += 7
            }
            return null
        }

        fun bytes(count: Int): ByteArray? {
            if (count < 0 || pos + count > end) return null
            return data.copyOfRange(pos, pos + count).also { pos += count }
        }
    }
}
