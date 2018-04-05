package sx.reactive.socket.client

import sx.reactive.socket.model.*
import sx.reactive.socket.utils.hexDump
import sx.reactive.socket.utils.stackDump
import org.slf4j.Logger
import reactor.core.publisher.Flux
import java.net.ConnectException

fun Flux<ClientSocketEvent>.dataReceivedSocketEvent(): Flux<ByteArray> {
    return this.filter { it is DataReceived }
        .map { it as DataReceived }
        .map { it.data }
}

// apply this function to the hold flow
fun Flux<ClientSocketEvent>.logSocketEvent(logger: Logger): Flux<ClientSocketEvent> {
    return this.scan { prevSocketEvent, socketEvent ->
        when (socketEvent) {
            is ClientInit -> Unit
            is ClientConnection -> logger.info("Connected: ${socketEvent.local} to ${socketEvent.remote}")
            is ClientDisconnection -> logger.info("Disconnected: ${socketEvent.local} to ${socketEvent.remote}")
            is ClientDataSent -> logger.trace("Send data (${socketEvent.from} -> ${socketEvent.to}): ${socketEvent.data.hexDump()}")
            is ClientDataReceived -> logger.trace("Received data (${socketEvent.from} -> ${socketEvent.to}): ${socketEvent.data.hexDump()}")
            is ClientSocketError -> {
                val omit = run {
                    if (socketEvent.e is ConnectException) {
                        if (prevSocketEvent is ClientSocketError) {
                            if (prevSocketEvent.e is ConnectException) return@run true
                        }
                    }
                    false
                }
                if (!omit) logger.error("Server socket error: ${socketEvent.address}\n${socketEvent.e.stackDump()}")
            }
        }
        socketEvent
    }
}
