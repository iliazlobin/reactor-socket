package sx.reactive.socket.server

import sx.reactive.socket.model.*
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.time.Duration

public fun multiClientUdpServer(bindAddress: InetSocketAddress, outbound: Flux<ByteArray> = Flux.never(), threadName: String = "udp-server", receiveBufferSize: Int = 1024, receiveTimeout: Int = 0, startDelayMs: Int = 500, reconnectionTimeout: Int = 3): Flux<ServerSocketEvent> {

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
                // we need to use this var because of we haven't function that do server socket creation job
                var serverSocket: DatagramSocket? = null
                val clientAddressesMutex = java.lang.Object()
                var clientAddresses: Set<InetSocketAddress> = setOf()

                try {
                    // TODO: wrap in function
                    serverSocket = DatagramSocket(bindAddress)
                    sink.next(ServerBindEvent(serverSocket.localSocketAddress as InetSocketAddress))

                    // with
                    var sendingTask: Disposable? = null
                    var receivingTask: Disposable? = null

                    val workFlow = Flux.create<WorkEvent> { workSink ->

                        sendingTask = outbound.publishOn(scheduler).subscribe { ba ->
                            // we need to lock all routine because of Socket object have mutable state
                            synchronized(clientAddressesMutex) {
                                var workedSockets: Set<InetSocketAddress> = setOf()
                                clientAddresses.forEach { socket ->
                                    try {
                                        serverSocket.send(DatagramPacket(ba, ba.size, socket))
                                        workSink.next(SentData)
                                        sink.next(ServerDataSent(ba, serverSocket.localSocketAddress, socket))
                                        workedSockets += socket
                                    } catch (e: Exception) {
                                        sink.next(ServerSocketError(bindAddress, e))
                                        // TODO: complete channel only at 1 client
//                                        workSink.complete()
                                    }
                                }
                                clientAddresses = workedSockets
                            }
                        }

                        receivingTask = scheduler.schedule {
                            while (true) {
                                try {
                                    serverSocket.receive(receivePacket)
                                    workSink.next(ReceivedData)

                                    val receivedSenderAddress = InetSocketAddress(receivePacket.address, receivePacket.port)

                                    synchronized(clientAddressesMutex) {
                                        clientAddresses += receivedSenderAddress
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

                    if (receiveTimeout > 0) {
                        val receivedDataFlow = workFlow.filter { it is ReceivedData }.publish().autoConnect()
                        receivedDataFlow.take(1).blockLast()
                        receivedDataFlow.timeout(Duration.ofSeconds(receiveTimeout.toLong())).blockLast()
                    } else {
                        workFlow.blockLast()
                    }

                    // we control state and needn't using mutex here
                    sendingTask!!.dispose()
                    receivingTask?.dispose()

                    // TODO: create special event
                    sink.next(ServerDisconnection(serverSocket.localSocketAddress as InetSocketAddress, serverSocket.localSocketAddress as InetSocketAddress))
                } catch (e: Exception) {
                    sink.next(ServerSocketError(bindAddress, e))
                }

                // we control state and needn't using mutex here
                serverSocket!!.close()

                if (reconnectionTimeout > 0) {
                    Thread.sleep(reconnectionTimeout * 1000.toLong())
                }
            }
        }
    }.publishOn(scheduler).publish().autoConnect()
}
