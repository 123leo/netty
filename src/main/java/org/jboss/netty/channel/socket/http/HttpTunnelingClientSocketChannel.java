/*
 * JBoss, Home of Professional Open Source
 *
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
 * by the @author tags. See the COPYRIGHT.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.netty.channel.socket.http;

import static org.jboss.netty.channel.Channels.*;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.AbstractChannel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineCoverage;
import org.jboss.netty.channel.ChannelSink;
import org.jboss.netty.channel.DefaultChannelPipeline;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.SocketChannel;
import org.jboss.netty.handler.codec.frame.DelimiterBasedFrameDecoder;
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.util.internal.LinkedTransferQueue;

/**
 * @author The Netty Project (netty-dev@lists.jboss.org)
 * @author Andy Taylor (andy.taylor@jboss.org)
 * @author Trustin Lee (tlee@redhat.com)
 * @version $Rev$, $Date$
 */
class HttpTunnelingClientSocketChannel extends AbstractChannel
        implements org.jboss.netty.channel.socket.SocketChannel {

    static final InternalLogger logger =
        InternalLoggerFactory.getInstance(HttpTunnelingClientSocketChannel.class);

    private final HttpTunnelingSocketChannelConfig config;
    private final Lock reconnectLock = new ReentrantLock();

    volatile boolean awaitingInitialResponse = true;

    private final Object writeLock = new Object();
    final Object interestOpsLock = new Object();

    volatile Thread workerThread;

    volatile String sessionId;

    volatile boolean closed = false;

    final BlockingQueue<byte[]> messages = new LinkedTransferQueue<byte[]>();

    private final ClientSocketChannelFactory clientSocketChannelFactory;

    volatile SocketChannel channel;

    private final DelimiterBasedFrameDecoder decoder = new DelimiterBasedFrameDecoder(8092, ChannelBuffers.wrappedBuffer(new byte[] { '\r', '\n' }));
    private final HttpTunnelingClientSocketChannel.ServletChannelHandler handler = new ServletChannelHandler();

    volatile HttpTunnelAddress remoteAddress;

    HttpTunnelingClientSocketChannel(
            ChannelFactory factory,
            ChannelPipeline pipeline,
            ChannelSink sink, ClientSocketChannelFactory clientSocketChannelFactory) {

        super(null, factory, pipeline, sink);

        this.clientSocketChannelFactory = clientSocketChannelFactory;

        createSocketChannel();
        config = new HttpTunnelingSocketChannelConfig(this);
        fireChannelOpen(this);
    }

    public HttpTunnelingSocketChannelConfig getConfig() {
        return config;
    }

    public InetSocketAddress getLocalAddress() {
        return channel.getLocalAddress();
    }

    public InetSocketAddress getRemoteAddress() {
        return channel.getRemoteAddress();
    }

    public boolean isBound() {
        return channel.isBound();
    }

    public boolean isConnected() {
        return channel.isConnected();
    }

    @Override
    protected boolean setClosed() {
        return super.setClosed();
    }

    @Override
    protected void setInterestOpsNow(int interestOps) {
        super.setInterestOpsNow(interestOps);
    }

    @Override
    public ChannelFuture write(Object message, SocketAddress remoteAddress) {
        if (remoteAddress == null || remoteAddress.equals(getRemoteAddress())) {
            return super.write(message, null);
        }
        else {
            return getUnsupportedOperationFuture();
        }
    }

    void connectAndSendHeaders(boolean reconnect, HttpTunnelAddress remoteAddress) throws SSLException {
        this.remoteAddress = remoteAddress;
        URI url = remoteAddress.getUri();
        if (reconnect) {
            closeSocket();
            createSocketChannel();
        }
        SocketAddress connectAddress = new InetSocketAddress(url.getHost(), url.getPort());
        channel.connect(connectAddress).awaitUninterruptibly();

        // Configure SSL
        HttpTunnelingSocketChannelConfig config = getConfig();
        SSLContext sslContext = config.getSslContext();
        if (sslContext != null) {
            URI uri = remoteAddress.getUri();
            SSLEngine engine = sslContext.createSSLEngine(
                    uri.getHost(), uri.getPort());

            // Configure the SSLEngine.
            engine.setUseClientMode(true);
            engine.setEnableSessionCreation(config.isEnableSslSessionCreation());
            String[] enabledCipherSuites = config.getEnabledSslCipherSuites();
            if (enabledCipherSuites != null) {
                engine.setEnabledCipherSuites(enabledCipherSuites);
            }
            String[] enabledProtocols = config.getEnabledSslProtocols();
            if (enabledProtocols != null) {
                engine.setEnabledProtocols(enabledProtocols);
            }

            SslHandler sslHandler = new SslHandler(engine);
            channel.getPipeline().addFirst("ssl", sslHandler);
            sslHandler.handshake(channel).awaitUninterruptibly();
        }

        // Send the HTTP request.
        StringBuilder builder = new StringBuilder();
        builder.append("POST ").append(url.getRawPath()).append(" HTTP/1.1").append(HttpTunnelingClientSocketPipelineSink.LINE_TERMINATOR).
                append("Host: ").append(url.getHost()).append(":").append(url.getPort()).append(HttpTunnelingClientSocketPipelineSink.LINE_TERMINATOR).
                append("Content-Type: application/octet-stream").append(HttpTunnelingClientSocketPipelineSink.LINE_TERMINATOR).append("Transfer-Encoding: chunked").
                append(HttpTunnelingClientSocketPipelineSink.LINE_TERMINATOR).append("Content-Transfer-Encoding: Binary").append(HttpTunnelingClientSocketPipelineSink.LINE_TERMINATOR).append("Connection: Keep-Alive").
                append(HttpTunnelingClientSocketPipelineSink.LINE_TERMINATOR);
        if (reconnect) {
            builder.append("Cookie: JSESSIONID=").append(sessionId).append(HttpTunnelingClientSocketPipelineSink.LINE_TERMINATOR);
        }
        builder.append(HttpTunnelingClientSocketPipelineSink.LINE_TERMINATOR);
        String msg = builder.toString();
        channel.write(ChannelBuffers.copiedBuffer(msg, "ASCII"));
    }

    /**
     *
     */
    private void createSocketChannel() {
        DefaultChannelPipeline channelPipeline = new DefaultChannelPipeline();
        channelPipeline.addLast("decoder", decoder);
        channelPipeline.addLast("handler", handler);
        channel = clientSocketChannelFactory.newChannel(channelPipeline);
    }

    int sendChunk(ChannelBuffer a) {
        int size = a.readableBytes();
        String hex = Integer.toHexString(size) + HttpTunnelingClientSocketPipelineSink.LINE_TERMINATOR;

        synchronized (writeLock) {
            ChannelFuture future = channel.write(ChannelBuffers.wrappedBuffer(
                    ChannelBuffers.copiedBuffer(hex, "ASCII"),
                    a,
                    ChannelBuffers.copiedBuffer(HttpTunnelingClientSocketPipelineSink.LINE_TERMINATOR, "ASCII")));
            future.awaitUninterruptibly();
        }

        return size + hex.length() + HttpTunnelingClientSocketPipelineSink.LINE_TERMINATOR.length();
    }

    byte[] receiveChunk() {
        byte[] buf = null;
        try {
            buf = messages.take();
        }
        catch (InterruptedException e) {
            // Ignore
        }
        return buf;
    }

    void reconnect() throws Exception {
        if (closed) {
            throw new IllegalStateException("channel closed");
        }
        if (reconnectLock.tryLock()) {
            try {
                awaitingInitialResponse = true;
                connectAndSendHeaders(true, remoteAddress);
            } finally {
                reconnectLock.unlock();
            }
        } else {
            try {
                reconnectLock.lock();
            } finally {
                reconnectLock.unlock();
            }
        }
    }

    void closeSocket() {
        if (setClosed()) {
            // Send the end of chunk.
            synchronized (writeLock) {
                ChannelFuture future = channel.write(ChannelBuffers.copiedBuffer(
                        "0" +
                        HttpTunnelingClientSocketPipelineSink.LINE_TERMINATOR +
                        HttpTunnelingClientSocketPipelineSink.LINE_TERMINATOR,
                        "ASCII"));
                future.awaitUninterruptibly();
            }

            closed = true;
            channel.close();
        }
    }

    void bindSocket(SocketAddress localAddress) {
        channel.bind(localAddress);
    }

    @ChannelPipelineCoverage("one")
    class ServletChannelHandler extends SimpleChannelUpstreamHandler {
        int nextChunkSize = -1;

        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
            ChannelBuffer buf = (ChannelBuffer) e.getMessage();
            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            if (awaitingInitialResponse) {
                String line = new String(bytes);
                if (line.contains("Set-Cookie")) {
                    int start = line.indexOf("JSESSIONID=") + 11;
                    int end = line.indexOf(";", start);
                    sessionId = line.substring(start, end);
                }
                else if (line.equals("")) {
                    awaitingInitialResponse = false;
                }
            }
            else {
                if(nextChunkSize == -1) {
                    String hex = new String(bytes);
                    nextChunkSize = Integer.parseInt(hex, 16);
                    if(nextChunkSize == 0) {
                        if(!closed) {
                            nextChunkSize = -1;
                            awaitingInitialResponse = true;
                            reconnect();
                        }
                    }
                }
                else {
                    messages.put(bytes);
                    nextChunkSize = -1;
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
            fireExceptionCaught(
                    HttpTunnelingClientSocketChannel.this,
                    e.getCause());
            channel.close();
        }
    }
}