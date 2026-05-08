@file:OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class, ExperimentalWasmInterop::class)

import kotlin.wasm.unsafe.withScopedMemoryAllocator

@WasmImport("wasi_snapshot_preview1", "fd_read")
private external fun wasiRead(fd: Int, iovs: Int, iovsLen: Int, nread: Int): Int

fun readln(): String? {
    val sb = StringBuilder()
    withScopedMemoryAllocator { alloc ->
        val buf   = alloc.allocate(1)
        val iov   = alloc.allocate(8)
        val nread = alloc.allocate(4)

        iov.storeInt(buf.address.toInt())
        (iov + 4).storeInt(1)

        while (true) {
            val err = wasiRead(0, iov.address.toInt(), 1, nread.address.toInt())
            if (err != 0) return null

            val n = nread.loadInt()
            if (n == 0) break

            val byte = buf.loadByte().toInt() and 0xFF
            if (byte == '\n'.code) break
            sb.append(byte.toChar())
        }
    }
    return if (sb.isEmpty()) null else sb.toString()
}