package sx.reactive.socket

import sx.reactive.ext.hot
import sx.reactive.ext.logByteArray
import sx.reactive.ext.logDebug
import sx.reactive.socket.client.dataReceivedSocketEvent
import sx.reactive.socket.client.tcpClient
import sx.reactive.socket.server.multiClientTcpServer
import sx.reactive.socket.server.tcpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.logging.LogLevel
import org.springframework.boot.test.context.ConfigFileApplicationContextInitializer
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import reactor.core.publisher.Flux
import java.net.InetSocketAddress
import java.time.Duration

@ExtendWith(SpringExtension::class)
@SpringBootConfiguration
@EnableAutoConfiguration
@ContextConfiguration(initializers = [ConfigFileApplicationContextInitializer::class])
@SpringBootTest
open class Test {
    val logger = LoggerFactory.getLogger(javaClass)

    // TODO: make test as non-interractive
    @Test
    fun clientServer() {
        val serverOutbound = Flux.generate<ByteArray, Int>({ 0 }, { s, sink ->
            sink.next(byteArrayOf(s.toByte()))
            s + 1
        })
            .delayElements(Duration.ofSeconds(1))
            .hot()
        val serverInbound = tcpServer(InetSocketAddress(21001), serverOutbound)
        serverInbound
            .logDebug(logger, "Server event: ")
            .subscribe()
        serverOutbound
            .logByteArray(logger, "Server outcoming data: ", LogLevel.TRACE)
            .subscribe()

//        val clientOutbound = Flux.generate<ByteArray, Int>({ 100 }, { s, sink ->
//            sink.next(byteArrayOf(s.toByte()))
//            (s + 1)
//        }).delayElements(Duration.ofSeconds(1))

        // UC: client connect when server was started already
        val clientInbound = tcpClient(InetSocketAddress("127.0.0.1", 21001), Flux.never())
        clientInbound
            .logDebug(logger, "Client event: ")
            .dataReceivedSocketEvent()
            .logByteArray(logger, "Client incoming data: ", LogLevel.TRACE)
            .subscribe()

        Thread.currentThread().join()
    }

    // TODO: make test as non-interractive
    @Test
    fun multiclientServer() {
        val serverOutbound = Flux.generate<ByteArray, Int>({ 0 }, { s, sink ->
            sink.next(byteArrayOf(s.toByte()))
            s + 1
        })
            .delayElements(Duration.ofSeconds(1))
            .hot()
        val serverInbound = multiClientTcpServer(InetSocketAddress(21001), serverOutbound)
        serverInbound
            .logDebug(logger, "Server event: ")
            .subscribe()
        serverOutbound
            .logByteArray(logger, "Server outcoming data: ", LogLevel.TRACE)
            .subscribe()

        val client1Inbound = tcpClient(InetSocketAddress("127.0.0.1", 21001), Flux.never())
        client1Inbound
            .logDebug(logger, "Client#1 event: ")
            .dataReceivedSocketEvent()
            .logByteArray(logger, "Client#1 incoming data: ", LogLevel.TRACE)
            .subscribe()

        val client2Inbound = tcpClient(InetSocketAddress("127.0.0.1", 21001), Flux.never())
        client2Inbound
            .logDebug(logger, "Client#2 event: ")
            .dataReceivedSocketEvent()
            .logByteArray(logger, "Client#2 incoming data: ", LogLevel.TRACE)
            .subscribe()

        Thread.currentThread().join()
    }
}
