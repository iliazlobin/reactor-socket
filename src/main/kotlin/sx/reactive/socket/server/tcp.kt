package sx.reactive.socket.server

import sx.reactive.socket.model.*
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration

public fun tcpServer(bindAddress: InetSocketAddress, outbound: Flux<ByteArray> = Flux.never(), threadName: String = "tcp-server", receiveBufferSize: Int = 1024, receiveTimeout: Int = 0, startDelayMs: Int = 500, reconnectionTimeout: Int = 3): Flux<ServerSocketEvent> {
    val port = bindAddress.port

    val scheduler = Schedulers.newElastic(threadName, 30, true)

    return Flux.create<ServerSocketEvent> { sink ->
        val receiveBuffer = ByteArray(receiveBufferSize)

        scheduler.schedule {

            if (startDelayMs > 0) {
                Thread.sleep(startDelayMs.toLong())
            }
            sink.next(ServerInit)

            while (true) {
                var serverSocket: ServerSocket? = null
                var clientSocket: Socket? = null

                try {
                    serverSocket = ServerSocket(port)
                    sink.next(ServerBindEvent(serverSocket.localSocketAddress as InetSocketAddress))

                    var sendingTask: Disposable? = null
                    var receivingTask: Disposable? = null

                    val workFlow = Flux.create<WorkEvent> { workSink ->

                        sendingTask = outbound.publishOn(scheduler).subscribe {
                            if (clientSocket != null) {
                                try {
                                    clientSocket?.getOutputStream()?.write(it)
                                    workSink.next(SentData)
                                    sink.next(ServerDataSent(it, clientSocket!!.localSocketAddress, clientSocket!!.remoteSocketAddress))
                                } catch (e: Exception) {
                                    sink.next(ServerSocketError(bindAddress, e))
                                    workSink.complete()
                                }
                            }
                        }

                        receivingTask = scheduler.schedule {
                            while (true) {
                                try {
                                    if (clientSocket == null) {
                                        clientSocket = serverSocket.accept()
                                        sink.next(ServerConnection(serverSocket.localSocketAddress as InetSocketAddress, clientSocket!!.remoteSocketAddress as InetSocketAddress))
                                        continue
                                    }

                                    val receivedBytes = clientSocket!!.getInputStream().read(receiveBuffer)
                                    workSink.next(ReceivedData)
                                    // TODO: redundancy copying (check in udp too)
                                    val receivedData = receiveBuffer.copyOf(receivedBytes)
                                    // TODO: mutex
                                    sink.next(ServerDataReceived(receivedData, clientSocket!!.remoteSocketAddress, clientSocket!!.localSocketAddress))
                                    continue
                                } catch (e: Exception) {
                                    sink.next(ServerSocketError(bindAddress, e))
                                    workSink.complete()
                                }
                                break
                            }
                        }
                    }.publishOn(scheduler)

                    if (receiveTimeout > 0) {
                        val receivedDataFlow = workFlow.filter { it is ReceivedData }.publish().autoConnect()
                        receivedDataFlow.take(1).blockLast()
                        receivedDataFlow.timeout(Duration.ofSeconds(receiveTimeout.toLong())).blockLast()
                    } else {
                        workFlow.blockLast()
                    }

                    sendingTask?.dispose()
                    receivingTask?.dispose()

                    sink.next(ServerDisconnection(serverSocket.localSocketAddress as InetSocketAddress, clientSocket?.remoteSocketAddress as InetSocketAddress))
                } catch (e: Exception) {
                    sink.next(ServerSocketError(bindAddress, e))
                }

                clientSocket?.close()
                serverSocket?.close()

                if (reconnectionTimeout > 0) {
                    Thread.sleep(reconnectionTimeout * 1000.toLong())
                }
            }
        }
    }.publishOn(scheduler).publish().autoConnect()
}
