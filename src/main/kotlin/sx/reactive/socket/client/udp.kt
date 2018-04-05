package sx.reactive.socket.client

import sx.reactive.socket.model.*
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.time.Duration

fun udpClient(connectAddress: InetSocketAddress, outbound: Flux<ByteArray> = Flux.never(), threadName: String = "udp-client", receiveBufferSize: Int = 1024, receiveTimeout: Int = 0, startDelayMs: Int = 500, reconnectionTimeout: Int = 3): Flux<ClientSocketEvent> {

    val scheduler = Schedulers.newElastic(threadName, 30, true)

    return Flux.create<ClientSocketEvent> { sink ->
        val receiveBuffer = ByteArray(receiveBufferSize)
        val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)

        scheduler.schedule {

            if (startDelayMs > 0) {
                Thread.sleep(startDelayMs.toLong())
            }
            sink.next(ClientInit)

            while (true) {
                var clientSocket: DatagramSocket? = null
                var serverAddress: SocketAddress? = null

                try {
                    clientSocket = DatagramSocket()

                    var sendingTask: Disposable? = null
                    var receivingTask: Disposable? = null

                    val workFlow = Flux.create<WorkEvent> { workSink ->

                        sendingTask = outbound.publishOn(scheduler).subscribe {
                            try {
                                clientSocket.send(DatagramPacket(it, it.size, connectAddress))
                                workSink.next(SentData)
                                sink.next(ClientDataSent(it, clientSocket.localSocketAddress, serverAddress))
                            } catch (e: Exception) {
                                sink.next(ClientSocketError(connectAddress, e))
                                workSink.complete()
                            }
                        }

                        receivingTask = scheduler.schedule {
                            while (true) {
                                try {
                                    clientSocket.receive(receivePacket)
                                    workSink.next(ReceivedData)

                                    if (serverAddress == null) {
                                        serverAddress = receivePacket.socketAddress as InetSocketAddress
                                        sink.next(ClientConnection(clientSocket.localSocketAddress as InetSocketAddress, serverAddress!!))
                                    }

                                    val receivedData = receivePacket.data.copyOf(receivePacket.length)
                                    sink.next(ClientDataReceived(receivedData, serverAddress, clientSocket.localSocketAddress))
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

                    sink.next(ClientDisconnection(clientSocket.localSocketAddress as InetSocketAddress, serverAddress!!))
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
