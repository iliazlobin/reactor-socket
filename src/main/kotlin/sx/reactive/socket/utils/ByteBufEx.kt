package sx.reactive.socket.utils

import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled

fun ByteArray.hexDump(): String {
    val bb = Unpooled.wrappedBuffer(this)
    return "\n" + ByteBufUtil.prettyHexDump(bb)
}
