package sx.reactive.socket.server

import sx.reactive.socket.model.*
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.time.Duration

public fun udpServer(bindAddress: InetSocketAddress, outbound: Flux<ByteArray> = Flux.never(), threadName: String = "udp-server", receiveBufferSize: Int = 1024, receiveTimeout: Int = 0, startDelayMs: Int = 500, reconnectionTimeout: Int = 3): Flux<ServerSocketEvent> {

    val scheduler = Schedulers.newElastic(threadName, 30, true)

    return Flux.create<ServerSocketEvent> { sink ->
        val receiveBuffer = ByteArray(receiveBufferSize)
        val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)

        scheduler.schedule {

            if (startDelayMs > 0) {
                Thread.sleep(startDelayMs.toLong())
            }

            sink.next(ServerInit)

            while (true) {
                var serverSocket: DatagramSocket? = null
                var clientAddress: InetSocketAddress? = null

                try {
                    serverSocket = DatagramSocket(bindAddress)
                    sink.next(ServerBindEvent(serverSocket.localSocketAddress as InetSocketAddress))

                    var sendingTask: Disposable? = null
                    var receivingTask: Disposable? = null

                    val workFlow = Flux.create<WorkEvent> { workSink ->

                        sendingTask = outbound.publishOn(scheduler).subscribe {
                            if (clientAddress != null) {
                                try {
                                    serverSocket.send(DatagramPacket(it, it.size, clientAddress))
                                    workSink.next(SentData)
                                    sink.next(ServerDataSent(it, clientAddress, serverSocket.localSocketAddress))
                                } catch (e: Exception) {
                                    sink.next(ServerSocketError(bindAddress, e))
                                    workSink.complete()
                                }
                            }
                        }

                        receivingTask = scheduler.schedule {
                            while (true) {
                                try {
                                    serverSocket.receive(receivePacket)
                                    workSink.next(ReceivedData)

                                    val receivedSenderAddress = InetSocketAddress(receivePacket.address, receivePacket.port)

                                    if (clientAddress == null) {
                                        clientAddress = receivedSenderAddress
                                        sink.next(ServerConnection(serverSocket.localSocketAddress as InetSocketAddress, clientAddress!!))
                                    }

                                    if (clientAddress != receivedSenderAddress) {
                                        continue
                                    }

                                    val receivedData = receivePacket.data.copyOf(receivePacket.length)
                                    sink.next(ServerDataReceived(receivedData, receivedSenderAddress, serverSocket.localSocketAddress))
                                    continue
                                } catch (e: Exception) {
                                    sink.next(ServerSocketError(bindAddress, e))
                                }
                                break
                            }
                            workSink.complete()
                        }
                    }.publishOn(scheduler)

                    try {
                        if (receiveTimeout > 0) {
                            val receivedDataFlow = workFlow.filter { it is ReceivedData }.publish().autoConnect()
                            receivedDataFlow.take(1).blockLast()
                            receivedDataFlow.timeout(Duration.ofSeconds(receiveTimeout.toLong())).blockLast()
                        } else {
                            workFlow.blockLast()
                        }
                    } catch (e: Exception) {
                        sink.next(ServerSocketError(bindAddress, e))
                    }

                    sendingTask?.dispose()
                    receivingTask?.dispose()

                    sink.next(ServerDisconnection(serverSocket.localSocketAddress as InetSocketAddress, clientAddress!!))
                } catch (e: Exception) {
                    sink.next(ServerSocketError(bindAddress, e))
                }

                serverSocket?.close()

                if (reconnectionTimeout > 0) {
                    Thread.sleep(reconnectionTimeout * 1000.toLong())
                }
            }
        }
    }.publishOn(scheduler).publish().autoConnect()
}
