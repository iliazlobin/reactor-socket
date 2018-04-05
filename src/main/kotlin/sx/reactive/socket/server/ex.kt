package sx.reactive.socket.server

import sx.reactive.socket.model.*
import sx.reactive.socket.utils.hexDump
import sx.reactive.socket.utils.stackDump
import org.slf4j.Logger
import reactor.core.publisher.Flux
import java.net.BindException
import java.net.ConnectException

fun Flux<ServerSocketEvent>.dataReceivedSocketEvent(): Flux<ByteArray> {
    return this.filter { it is DataReceived }
        .map { it as DataReceived }
        .map { it.data }
}

fun Flux<ServerSocketEvent>.logSocketEvent(logger: Logger): Flux<ServerSocketEvent> {
    return this.scan { prevSocketEvent, socketEvent ->
        when (socketEvent) {
            is ServerInit -> Unit
            is ServerBindEvent -> logger.info("Binded: ${socketEvent.local}")
            is ServerConnection -> logger.info("Client connected: ${socketEvent.local} to ${socketEvent.remote}")
            is ServerDisconnection -> logger.info("Client disconnected: ${socketEvent.local} to ${socketEvent.remote}")
            is ServerDataSent -> logger.trace("Send data (${socketEvent.from} -> ${socketEvent.to}): ${socketEvent.data.hexDump()}")
            is ServerDataReceived -> logger.trace("Received data (${socketEvent.from} -> ${socketEvent.to}): ${socketEvent.data.hexDump()}")
            is ServerSocketError -> {
                val omit = run {
                    if (prevSocketEvent is ServerSocketError) {
                        if ((socketEvent.e is ConnectException) and (prevSocketEvent.e is ConnectException)) return@run true
                        if ((socketEvent.e is BindException) and (prevSocketEvent.e is BindException)) return@run true
                    }
                    false
                }
                if (!omit) logger.error("Server socket error: ${socketEvent.address}\n${socketEvent.e.stackDump()}")
            }
        }
        socketEvent
    }
}
