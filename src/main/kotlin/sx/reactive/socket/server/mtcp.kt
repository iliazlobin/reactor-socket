package sx.reactive.socket.server

import io.vavr.control.Try
import sx.reactive.socket.model.*
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration

public fun multiClientTcpServer(bindAddress: InetSocketAddress, outbound: Flux<ByteArray> = Flux.never(), threadName: String = "tcp-server", receiveBufferSize: Int = 1024, receiveTimeout: Int = 0, startDelayMs: Int = 500, reconnectionTimeout: Int = 3): Flux<ServerSocketEvent> {
    val port = bindAddress.port

    val scheduler = Schedulers.newElastic(threadName, 30, true)

    return Flux.create<ServerSocketEvent> { sink ->

        scheduler.schedule {

            if (startDelayMs > 0) {
                Thread.sleep(startDelayMs.toLong())
            }
            sink.next(ServerInit)

            while (true) {
                // we need to use this var because of we haven't function that do server socket creation job
                var serverSocket: ServerSocket? = null
                val clientSocketsMutex = java.lang.Object()
                var clientSockets: List<Socket> = listOf()

                try {
                    // TODO: wrap in function
                    serverSocket = ServerSocket(port)
                    sink.next(ServerBindEvent(serverSocket.localSocketAddress as InetSocketAddress))

                    // with
                    var sendingTask: Disposable? = null
                    var acceptingTask: Disposable? = null
                    val receivingTasksMutex = java.lang.Object()
                    var receivingTasks: List<Disposable> = listOf()

                    val workFlow = Flux.create<WorkEvent> { workSink ->

                        sendingTask = outbound.publishOn(scheduler).subscribe { ba ->
                            // we need to lock all routine because of Socket object have mutable state
                            synchronized(clientSocketsMutex) {
                                var workedSockets: List<Socket> = listOf()
                                clientSockets.forEach { socket ->
                                    try {
                                        socket.getOutputStream().write(ba)
                                        // send to every client
                                        workSink.next(SentData)
                                        sink.next(ServerDataSent(ba, socket.localSocketAddress, socket.remoteSocketAddress))
                                        workedSockets += socket
                                    } catch (e: Exception) {
                                        sink.next(ServerSocketError(bindAddress, e))
                                        // TODO: complete channel only at 1 client
//                                        workSink.complete()
                                    }
                                }
                                clientSockets = workedSockets
                            }
                        }

                        acceptingTask = scheduler.schedule {
                            while (true) {

                                Try.ofCallable {
                                    serverSocket.accept()
                                }
                                    .onSuccess { clientSocket ->
                                        sink.next(ServerConnection(serverSocket.localSocketAddress as InetSocketAddress, clientSocket.remoteSocketAddress as InetSocketAddress))

                                        synchronized(clientSocketsMutex) { clientSockets += clientSocket }

                                        var receivingTask: Disposable? = null
                                        val cancelTask: () -> Unit = {
                                            if (receivingTask != null) {
                                                synchronized(receivingTasksMutex) { receivingTasks -= receivingTask!! }
                                            }
                                        }
                                        receivingTask = scheduler.schedule {
                                            while (true) {
                                                val receiveBuffer = ByteArray(receiveBufferSize)
                                                try {
                                                    val r = clientSocket.getInputStream().read(receiveBuffer)
                                                    if (r == -1) {
                                                        // TODO: event that client disconnected
                                                        break
                                                    }
                                                } catch (e: IOException) {
                                                    // channel unavailable
                                                    // TODO: event that client disconnected
                                                    break
                                                }
                                                // receive from any client
                                                // TODO: use client discrimination
                                                workSink.next(ReceivedData)
                                                sink.next(ServerDataReceived(receiveBuffer, clientSocket.remoteSocketAddress, clientSocket.localSocketAddress))
                                            }
                                            cancelTask()
                                        }
                                        synchronized(receivingTasksMutex) { receivingTasks += receivingTask }
                                    }
                                    .onFailure { e ->
                                        sink.next(ServerSocketError(bindAddress, e))
                                        workSink.complete()
                                    }
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

                    // we control state and needn't using mutex here
                    sendingTask!!.dispose()
                    acceptingTask!!.dispose()
                    receivingTasks.forEach { task ->
                        task.dispose()
                    }

                    // TODO: create special event
                    sink.next(ServerDisconnection(serverSocket.localSocketAddress as InetSocketAddress, serverSocket.localSocketAddress as InetSocketAddress))
                } catch (e: Exception) {
                    sink.next(ServerSocketError(bindAddress, e))
                }

                // we control state and needn't using mutex here
                serverSocket!!.close()
                clientSockets.forEach { socket ->
                    socket.close()
                }

                if (reconnectionTimeout > 0) {
                    Thread.sleep(reconnectionTimeout * 1000.toLong())
                }
            }
        }
    }.publishOn(scheduler).publish().autoConnect()
}
