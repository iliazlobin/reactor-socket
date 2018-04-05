package sx.reactive.socket.client

import sx.reactive.socket.model.*
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration

// TODO: add handle for disconnection controlling
fun tcpClient(connectAddress: InetSocketAddress, outbound: Flux<ByteArray> = Flux.never(), threadName: String = "tcp-client", receiveBufferSize: Int = 1024, receiveTimeout: Int = 0, startDelayMs: Int = 500, reconnectionTimeout: Int = 3): Flux<ClientSocketEvent> {
    val address = connectAddress.hostName
    val port = connectAddress.port

    val scheduler = Schedulers.newElastic(threadName, 30, true)

    return Flux.create<ClientSocketEvent> { sink ->
        val receiveBuffer = ByteArray(receiveBufferSize)

        scheduler.schedule {

            if (startDelayMs > 0) {
                Thread.sleep(startDelayMs.toLong())
            }
            sink.next(ClientInit)

            while (true) {
                var clientSocket: Socket? = null

                try {
                    clientSocket = Socket(address, port)
                    sink.next(ClientConnection(clientSocket.localSocketAddress, clientSocket.remoteSocketAddress))

                    var sendingTask: Disposable? = null
                    var receivingTask: Disposable? = null

                    val workFlow = Flux.create<WorkEvent> { workSink ->

                        sendingTask = outbound.publishOn(scheduler).subscribe {
                                try {
                                    clientSocket.getOutputStream().write(it)
                                    workSink.next(SentData)
                                    sink.next(ClientDataSent(it, clientSocket.localSocketAddress, clientSocket.remoteSocketAddress))
                                } catch (e: Exception) {
                                    sink.next(ClientSocketError(connectAddress, e))
                                    workSink.complete()
                                }
                            }

                        receivingTask = scheduler.schedule {
                            while (true) {
                                try {
                                    val receivedBytes = clientSocket.getInputStream().read(receiveBuffer)
                                    workSink.next(ReceivedData)
                                    val receivedData = receiveBuffer.copyOf(receivedBytes)
                                    sink.next(ClientDataReceived(receivedData, clientSocket.remoteSocketAddress, clientSocket.localSocketAddress))
                                    continue
                                } catch (e: Exception) {
                                    sink.next(ClientSocketError(connectAddress, e))
                                }
                                break
                            }
                            workSink.complete()
                        }
                    }.publishOn(scheduler)

                    try {
                        if (receiveTimeout > 0) {
                            workFlow.filter { it is ReceivedData }.timeout(Duration.ofSeconds(receiveTimeout.toLong())).blockLast()
                        } else {
                            workFlow.blockLast()
                        }
                    } catch (e: Exception) {
                        sink.next(ClientSocketError(connectAddress, e))
                    }

                    sendingTask?.dispose()
                    receivingTask?.dispose()

                    sink.next(ClientDisconnection(clientSocket.localSocketAddress, clientSocket.remoteSocketAddress))
                } catch (e: Exception) {
                    sink.next(ClientSocketError(connectAddress, e))
                }

                clientSocket?.close()

                if (reconnectionTimeout > 0) {
                    Thread.sleep(reconnectionTimeout * 1000.toLong())
                }
            }
        }
    }.publishOn(scheduler).publish().autoConnect()
}
