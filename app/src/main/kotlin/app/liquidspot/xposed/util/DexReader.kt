package app.liquidspot.xposed.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

class DexReader(bytes: ByteArray) {
    class MethodRef(val owner: String, val name: String, val params: List<String>, val returns: String)

    private val buf: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private val stringCount = buf.getInt(56)
    private val stringOffset = buf.getInt(60)
    private val typeOffset = buf.getInt(68)
    private val protoOffset = buf.getInt(76)
    val methodCount = buf.getInt(88)
    private val methodOffset = buf.getInt(92)
    private val classCount = buf.getInt(96)
    private val classOffset = buf.getInt(100)
    private val strings = HashMap<Int, String>()
    private var cursor = 0

    private fun u16(at: Int) = buf.getShort(at).toInt() and 0xFFFF

    private fun u32(at: Int) = buf.getInt(at)

    private fun uleb(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = buf.get(cursor++).toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    fun string(index: Int): String = strings.getOrPut(index) {
        cursor = u32(stringOffset + index * 4)
        val length = uleb()
        val out = StringBuilder(length)
        while (true) {
            val a = buf.get(cursor++).toInt() and 0xFF
            if (a == 0) break
            when {
                a < 0x80 -> out.append(a.toChar())
                a and 0xE0 == 0xC0 -> {
                    val b = buf.get(cursor++).toInt() and 0x3F
                    out.append((((a and 0x1F) shl 6) or b).toChar())
                }
                else -> {
                    val b = buf.get(cursor++).toInt() and 0x3F
                    val c = buf.get(cursor++).toInt() and 0x3F
                    out.append((((a and 0x0F) shl 12) or (b shl 6) or c).toChar())
                }
            }
        }
        out.toString()
    }

    fun findString(text: String): Int {
        var low = 0
        var high = stringCount - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cmp = string(mid).compareTo(text)
            when {
                cmp == 0 -> return mid
                cmp < 0 -> low = mid + 1
                else -> high = mid - 1
            }
        }
        return -1
    }

    fun type(index: Int): String = string(u32(typeOffset + index * 4))

    fun method(index: Int): MethodRef {
        val at = methodOffset + index * 8
        val proto = protoOffset + u16(at + 2) * 12
        val paramsAt = u32(proto + 8)
        val params = if (paramsAt == 0) emptyList() else List(u32(paramsAt)) { type(u16(paramsAt + 4 + it * 2)) }
        return MethodRef(type(u16(at)), string(u32(at + 4)), params, type(u32(proto + 4)))
    }

    fun paramCount(index: Int): Int {
        val paramsAt = u32(protoOffset + u16(methodOffset + index * 8 + 2) * 12 + 8)
        return if (paramsAt == 0) 0 else u32(paramsAt)
    }

    class Code(val reader: DexReader, val method: Int, private val at: Int) {
        val units: Int get() = reader.u32(at + 12)

        fun forEach(block: (op: Int, unit: Int) -> Boolean) {
            val base = at + 16
            val total = units
            var p = 0
            while (p < total) {
                val word = reader.u16(base + p * 2)
                val op = word and 0xFF
                if (!block(op, base + p * 2)) return
                p += when {
                    op == 0 && word ushr 8 == 1 -> reader.u16(base + p * 2 + 2) * 2 + 4
                    op == 0 && word ushr 8 == 2 -> reader.u16(base + p * 2 + 2) * 4 + 2
                    op == 0 && word ushr 8 == 3 -> ((reader.u32(base + p * 2 + 4) * reader.u16(base + p * 2 + 2) + 1) ushr 1) + 4
                    else -> WIDTH[op].toInt()
                }
            }
        }

        fun index16(unit: Int) = reader.u16(unit + 2)

        fun index32(unit: Int) = reader.u32(unit + 2)
    }

    fun forEachCode(block: (Code) -> Unit) {
        for (c in 0 until classCount) {
            val data = u32(classOffset + c * 32 + 24)
            if (data == 0) continue
            cursor = data
            val staticFields = uleb()
            val instanceFields = uleb()
            val direct = uleb()
            val virtual = uleb()
            repeat(staticFields + instanceFields) {
                uleb()
                uleb()
            }
            var method = 0
            for (k in 0 until direct + virtual) {
                if (k == direct) method = 0
                method += uleb()
                uleb()
                val code = uleb()
                if (code != 0) {
                    val resume = cursor
                    block(Code(this, method, code))
                    cursor = resume
                }
            }
        }
    }

    companion object {
        private val WIDTH = ByteArray(256) { 1 }.also { w ->
            fun set(from: Int, to: Int, width: Int) {
                for (i in from..to) w[i] = width.toByte()
            }
            set(0x02, 0x02, 2)
            set(0x03, 0x03, 3)
            set(0x05, 0x05, 2)
            set(0x06, 0x06, 3)
            set(0x08, 0x08, 2)
            set(0x09, 0x09, 3)
            set(0x13, 0x13, 2)
            set(0x14, 0x14, 3)
            set(0x15, 0x16, 2)
            set(0x17, 0x17, 3)
            set(0x18, 0x18, 5)
            set(0x19, 0x1A, 2)
            set(0x1B, 0x1B, 3)
            set(0x1C, 0x1C, 2)
            set(0x1F, 0x20, 2)
            set(0x22, 0x23, 2)
            set(0x24, 0x26, 3)
            set(0x29, 0x29, 2)
            set(0x2A, 0x2C, 3)
            set(0x2D, 0x3D, 2)
            set(0x44, 0x6D, 2)
            set(0x6E, 0x72, 3)
            set(0x74, 0x78, 3)
            set(0x90, 0xAF, 2)
            set(0xD0, 0xE2, 2)
            set(0xFA, 0xFB, 4)
            set(0xFC, 0xFD, 3)
            set(0xFE, 0xFF, 2)
        }

        fun className(descriptor: String): String = descriptor.substring(1, descriptor.length - 1).replace('/', '.')
    }
}
