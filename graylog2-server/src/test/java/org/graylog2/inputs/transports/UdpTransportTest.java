/**
 * This file is part of Graylog.
 *
 * Graylog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog2.inputs.transports;

import com.github.joschi.jadconfig.util.Size;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.Uninterruptibles;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.DatagramPacketDecoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.apache.commons.lang3.SystemUtils;
import org.graylog2.plugin.LocalMetricRegistry;
import org.graylog2.plugin.configuration.Configuration;
import org.graylog2.plugin.configuration.ConfigurationRequest;
import org.graylog2.plugin.inputs.MessageInput;
import org.graylog2.plugin.inputs.MisfireException;
import org.graylog2.plugin.inputs.transports.NettyTransport;
import org.graylog2.plugin.inputs.util.ThroughputCounter;
import org.graylog2.shared.SuppressForbidden;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.jayway.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class UdpTransportTest {
    private static final String BIND_ADDRESS = "127.0.0.1";
    private static final int PORT = 0;
    private static final int RECV_BUFFER_SIZE = 1024;
    private static final Map<String, Object> CONFIG_SOURCE = ImmutableMap.of(
            NettyTransport.CK_BIND_ADDRESS, BIND_ADDRESS,
            NettyTransport.CK_PORT, PORT,
            NettyTransport.CK_RECV_BUFFER_SIZE, RECV_BUFFER_SIZE);
    private static final Configuration CONFIGURATION = new Configuration(CONFIG_SOURCE);

    private final NettyTransportConfiguration nettyTransportConfiguration = new NettyTransportConfiguration("nio", "jdk", 1);
    private UdpTransport udpTransport;
    private NioEventLoopGroup eventLoopGroup;
    private ThroughputCounter throughputCounter;
    private LocalMetricRegistry localMetricRegistry;

    @Before
    @SuppressForbidden("Executors#newSingleThreadExecutor() is okay for tests")
    public void setUp() throws Exception {
        eventLoopGroup = new NioEventLoopGroup();
        throughputCounter = new ThroughputCounter(eventLoopGroup);
        localMetricRegistry = new LocalMetricRegistry();
        udpTransport = new UdpTransport(CONFIGURATION, eventLoopGroup, nettyTransportConfiguration, throughputCounter, localMetricRegistry);
    }

    @After
    public void tearDown() {
        eventLoopGroup.shutdownGracefully();
    }

    @Test
    public void transportReceivesDataSmallerThanRecvBufferSize() throws Exception {
        final CountingChannelUpstreamHandler handler = new CountingChannelUpstreamHandler();
        final UdpTransport transport = launchTransportForBootStrapTest(handler);
        final InetSocketAddress localAddress = (InetSocketAddress) transport.getLocalAddress();

        sendUdpDatagram(BIND_ADDRESS, localAddress.getPort(), 100);
        await().atMost(5, TimeUnit.SECONDS).until(() -> !handler.getBytesWritten().isEmpty());
        transport.stop();

        assertThat(handler.getBytesWritten()).containsOnly(100);
    }

    @Test
    public void transportReceivesDataExactlyRecvBufferSize() throws Exception {
        final CountingChannelUpstreamHandler handler = new CountingChannelUpstreamHandler();
        final UdpTransport transport = launchTransportForBootStrapTest(handler);
        final InetSocketAddress localAddress = (InetSocketAddress) transport.getLocalAddress();

        // This will be variable depending on the version of the IP protocol and the UDP packet size.
        final int udpOverhead = 16;
        final int maxPacketSize = RECV_BUFFER_SIZE - udpOverhead;

        sendUdpDatagram(BIND_ADDRESS, localAddress.getPort(), maxPacketSize);
        await().atMost(5, TimeUnit.SECONDS).until(() -> !handler.getBytesWritten().isEmpty());
        transport.stop();

        assertThat(handler.getBytesWritten()).containsOnly(maxPacketSize);
    }

    @Test
    public void transportDiscardsDataLargerRecvBufferSizeOnMacOsX() throws Exception {
        assumeTrue("Skipping test intended for MacOS X systems", SystemUtils.IS_OS_MAC_OSX);

        final CountingChannelUpstreamHandler handler = new CountingChannelUpstreamHandler();
        final UdpTransport transport = launchTransportForBootStrapTest(handler);
        final InetSocketAddress localAddress = (InetSocketAddress) transport.getLocalAddress();

        sendUdpDatagram(BIND_ADDRESS, localAddress.getPort(), 2 * RECV_BUFFER_SIZE);
        Uninterruptibles.sleepUninterruptibly(2, TimeUnit.SECONDS);

        transport.stop();

        assertThat(handler.getBytesWritten()).isEmpty();
    }

    @Test
    public void transportTruncatesDataLargerRecvBufferSizeOnLinux() throws Exception {
        assumeTrue("Skipping test intended for Linux systems", SystemUtils.IS_OS_LINUX);

        final CountingChannelUpstreamHandler handler = new CountingChannelUpstreamHandler();
        final UdpTransport transport = launchTransportForBootStrapTest(handler);
        final InetSocketAddress localAddress = (InetSocketAddress) transport.getLocalAddress();

        sendUdpDatagram(BIND_ADDRESS, localAddress.getPort(), 2 * RECV_BUFFER_SIZE);
        await().atMost(5, TimeUnit.SECONDS).until(() -> !handler.getBytesWritten().isEmpty());
        transport.stop();

        assertThat(handler.getBytesWritten()).containsExactly(RECV_BUFFER_SIZE);
    }

    private UdpTransport launchTransportForBootStrapTest(final MessageToMessageDecoder<ByteBuf> channelHandler) throws MisfireException {
        final UdpTransport transport = new UdpTransport(CONFIGURATION, eventLoopGroup, nettyTransportConfiguration, throughputCounter, new LocalMetricRegistry()) {
            @Override
            protected LinkedHashMap<String, Callable<? extends ChannelHandler>> getBaseChannelHandlers(MessageInput input) {
                final LinkedHashMap<String, Callable<? extends ChannelHandler>> handlers = new LinkedHashMap<>();
                handlers.put("logging", () -> new LoggingHandler(LogLevel.INFO));
                handlers.put("counter", () -> new DatagramPacketDecoder(channelHandler));
                handlers.putAll(super.getBaseChannelHandlers(input));
                return handlers;
            }
        };

        final MessageInput messageInput = mock(MessageInput.class);
        when(messageInput.getId()).thenReturn("TEST");
        when(messageInput.getName()).thenReturn("TEST");

        transport.launch(messageInput);

        return transport;
    }

    @Test
    public void receiveBufferSizeIsDefaultSize() throws Exception {
        assertThat(udpTransport.getBootstrap(mock(MessageInput.class)).config().options().get(ChannelOption.SO_RCVBUF)).isEqualTo(RECV_BUFFER_SIZE);
    }

    @Test
    public void receiveBufferSizeIsNotLimited() throws Exception {
        final int recvBufferSize = Ints.saturatedCast(Size.megabytes(1L).toBytes());
        ImmutableMap<String, Object> source = ImmutableMap.of(
                NettyTransport.CK_BIND_ADDRESS, BIND_ADDRESS,
                NettyTransport.CK_PORT, PORT,
                NettyTransport.CK_RECV_BUFFER_SIZE, recvBufferSize);
        Configuration config = new Configuration(source);
        UdpTransport udpTransport = new UdpTransport(config, eventLoopGroup, nettyTransportConfiguration, throughputCounter, new LocalMetricRegistry());

        assertThat(udpTransport.getBootstrap(mock(MessageInput.class)).config().options().get(ChannelOption.SO_RCVBUF)).isEqualTo(recvBufferSize);
    }

    @Test
    public void receiveBufferSizePredictorIsUsingDefaultSize() throws Exception {
        FixedRecvByteBufAllocator recvByteBufAllocator =
                (FixedRecvByteBufAllocator) udpTransport.getBootstrap(mock(MessageInput.class)).config().options().get(ChannelOption.RCVBUF_ALLOCATOR);
        assertThat(recvByteBufAllocator.newHandle().guess()).isEqualTo(RECV_BUFFER_SIZE);
    }

    @Test
    public void getMetricSetReturnsLocalMetricRegistry() {
        assertThat(udpTransport.getMetricSet()).isSameAs(localMetricRegistry);
    }

    @Test
    public void testDefaultReceiveBufferSize() throws Exception {
        final UdpTransport.Config config = new UdpTransport.Config();
        final ConfigurationRequest requestedConfiguration = config.getRequestedConfiguration();

        assertThat(requestedConfiguration.getField(NettyTransport.CK_RECV_BUFFER_SIZE).getDefaultValue()).isEqualTo(262144);
    }

    private void sendUdpDatagram(String hostname, int port, int size) throws IOException {
        final InetAddress address = InetAddress.getByName(hostname);
        final byte[] data = new byte[size];
        final DatagramPacket packet = new DatagramPacket(data, data.length, address, port);

        try (DatagramSocket toSocket = new DatagramSocket()) {
            toSocket.send(packet);
        }
    }

    public static class CountingChannelUpstreamHandler extends MessageToMessageDecoder<ByteBuf> {
        private final List<Integer> bytesWritten = new ArrayList<>();

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
            synchronized (bytesWritten) {
                bytesWritten.add(msg.readableBytes());
            }
        }

        List<Integer> getBytesWritten() {
            return bytesWritten;
        }
    }
}