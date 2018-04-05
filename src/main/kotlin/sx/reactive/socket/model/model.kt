package sx.reactive.socket.model

import java.net.SocketAddress

sealed class SocketEvent

sealed class ServerSocketEvent : SocketEvent()
sealed class ClientSocketEvent : SocketEvent()


object ServerInit: ServerSocketEvent()
object ClientInit: ClientSocketEvent()


data class ServerBindEvent(
    val local: SocketAddress
) : ServerSocketEvent()


interface LinkEvent {
    val local: SocketAddress
    val remote: SocketAddress
}

data class ServerConnection(
    override val local: SocketAddress,
    override val remote: SocketAddress
) : ServerSocketEvent(), LinkEvent

data class ServerDisconnection(
    override val local: SocketAddress,
    override val remote: SocketAddress
) : ServerSocketEvent(), LinkEvent

data class ClientConnection(
    override val local: SocketAddress,
    override val remote: SocketAddress
) : ClientSocketEvent(), LinkEvent

data class ClientDisconnection(
    override val local: SocketAddress,
    override val remote: SocketAddress
) : ClientSocketEvent(), LinkEvent


interface DataEvent {
    val data: ByteArray
    val from: SocketAddress?
    val to: SocketAddress?
}

interface DataSent : DataEvent {
    override val data: ByteArray
}

interface DataReceived : DataEvent {
    override val data: ByteArray
}

// TODO: realize this one for multiclient server
data class ServerDataSent(
    override val data: ByteArray,
    override val from: SocketAddress?,
    override val to: SocketAddress?
) : ServerSocketEvent(), DataSent

data class ServerDataReceived(
    override val data: ByteArray,
    override val from: SocketAddress?,
    override val to: SocketAddress?
) : ServerSocketEvent(), DataReceived

data class ClientDataSent(
    override val data: ByteArray,
    override val from: SocketAddress?,
    override val to: SocketAddress?
) : ClientSocketEvent(), DataSent

data class ClientDataReceived(
    override val data: ByteArray,
    override val from: SocketAddress?,
    override val to: SocketAddress?
) : ClientSocketEvent(), DataReceived


interface SocketError {
    abstract val address: SocketAddress
    abstract val e: Throwable
}

data class ServerSocketError(
    override val address: SocketAddress,
    override val e: Throwable
) : ServerSocketEvent(), SocketError

data class ClientSocketError(
    override val address: SocketAddress,
    override val e: Throwable
) : ClientSocketEvent(), SocketError


// inner purposes
sealed class WorkEvent
object ReceivedData : WorkEvent()
object SentData : WorkEvent()
