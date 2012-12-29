/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.apache.mina.transport.nio;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.apache.mina.api.IdleStatus;
import org.apache.mina.api.IoFuture;
import org.apache.mina.api.IoSession;
import org.apache.mina.service.executor.InOrderHandlerExecutor;
import org.apache.mina.service.executor.IoHandlerExecutor;
import org.apache.mina.service.idlechecker.IdleChecker;
import org.apache.mina.service.idlechecker.IndexedIdleChecker;
import org.apache.mina.transport.tcp.AbstractTcpClient;
import org.apache.mina.transport.tcp.TcpSessionConfig;
import org.apache.mina.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class implements a TCP NIO based client.
 * 
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public class NioTcpClient extends AbstractTcpClient {

    /** A logger for this class */
    static final Logger LOG = LoggerFactory.getLogger(NioTcpClient.class);

    // the SelectorLoop for connecting the sessions
    private final SelectorLoop connectSelectorLoop;

    // the Selectorloop for handling read/write session events
    private final SelectorLoopPool readWriteSelectorPool;

    // for detecting idle session
    private IdleChecker idleChecker;

    /**
     * Create a TCP client with new selector pool of default size and a {@link IoHandlerExecutor} of default type (
     * {@link InOrderHandlerExecutor})
     */
    public NioTcpClient() {
        this(new NioSelectorLoop("connect", 0), new FixedSelectorLoopPool(
                Runtime.getRuntime().availableProcessors() + 1), null);
    }

    /**
     * Create a TCP client with provided selector loops pool. We will use one SelectorLoop get from the pool to manage
     * the OP_CONNECT events. If the pool contains only one SelectorLoop, then all the events will be managed by the
     * same Selector.
     * 
     * @param readWriteSelectorLoop the pool of selector loop for handling read/write events of connected sessions
     * @param ioHandlerExecutor used for executing IoHandler event in another pool of thread (not in the low level I/O
     *        one). Use <code>null</code> if you don't want one. Be careful, the IoHandler processing will block the I/O
     *        operations.
     */
    public NioTcpClient(SelectorLoopPool selectorLoopPool, IoHandlerExecutor handlerExecutor) {
        super(handlerExecutor);
        this.connectSelectorLoop = selectorLoopPool.getSelectorLoop();
        this.readWriteSelectorPool = selectorLoopPool;
    }

    /**
     * Create a TCP client with provided selector loops pool
     * 
     * @param connectSelectorLoop the selector loop for handling connection events (connection of new session)
     * @param readWriteSelectorLoop the pool of selector loop for handling read/write events of connected sessions
     * @param ioHandlerExecutor used for executing IoHandler event in another pool of thread (not in the low level I/O
     *        one). Use <code>null</code> if you don't want one. Be careful, the IoHandler processing will block the I/O
     *        operations.
     */
    public NioTcpClient(SelectorLoop connectSelectorLoop, SelectorLoopPool readWriteSelectorLoop,
            IoHandlerExecutor handlerExecutor) {
        super(handlerExecutor);
        this.connectSelectorLoop = connectSelectorLoop;
        this.readWriteSelectorPool = readWriteSelectorLoop;
        idleChecker = new IndexedIdleChecker();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IoFuture<IoSession> connect(SocketAddress remoteAddress) throws IOException {
        Assert.assertNotNull(remoteAddress, "remoteAddress");

        SocketChannel clientSocket = SocketChannel.open();

        clientSocket.socket().setSoTimeout(getConnectTimeoutMillis());
        // non blocking
        clientSocket.configureBlocking(false);

        // apply idle configuration
        final NioTcpSession session = new NioTcpSession(this, clientSocket, readWriteSelectorPool.getSelectorLoop(),
                idleChecker);
        final TcpSessionConfig config = getSessionConfig();

        session.getConfig().setIdleTimeInMillis(IdleStatus.READ_IDLE, config.getIdleTimeInMillis(IdleStatus.READ_IDLE));
        session.getConfig().setIdleTimeInMillis(IdleStatus.WRITE_IDLE,
                config.getIdleTimeInMillis(IdleStatus.WRITE_IDLE));

        // apply the default service socket configuration
        final Boolean keepAlive = config.isKeepAlive();

        if (keepAlive != null) {
            session.getConfig().setKeepAlive(keepAlive);
        }

        final Boolean oobInline = config.isOobInline();

        if (oobInline != null) {
            session.getConfig().setOobInline(oobInline);
        }

        final Boolean reuseAddress = config.isReuseAddress();

        if (reuseAddress != null) {
            session.getConfig().setReuseAddress(reuseAddress);
        }

        final Boolean tcpNoDelay = config.isTcpNoDelay();

        if (tcpNoDelay != null) {
            session.getConfig().setTcpNoDelay(tcpNoDelay);
        }

        final Integer receiveBufferSize = config.getReceiveBufferSize();

        if (receiveBufferSize != null) {
            session.getConfig().setReceiveBufferSize(receiveBufferSize);
        }

        final Integer sendBufferSize = config.getSendBufferSize();

        if (sendBufferSize != null) {
            session.getConfig().setSendBufferSize(sendBufferSize);
        }

        final Integer trafficClass = config.getTrafficClass();

        if (trafficClass != null) {
            session.getConfig().setTrafficClass(trafficClass);
        }

        final Integer soLinger = config.getSoLinger();

        if (soLinger != null) {
            session.getConfig().setSoLinger(soLinger);
        }

        // Set the secured flag if the service is to be used over SSL/TLS
        if (config.isSecured()) {
            session.initSecure(config.getSslContext());
        }

        // connect to a running server
        boolean connected = clientSocket.connect(remoteAddress);

        NioTcpSession.ConnectFuture connectFuture = new NioTcpSession.ConnectFuture();
        session.setConnectFuture(connectFuture);

        if (!connected) {
            // async connection, let's the connection complete in background, the selector loop will dectect when the
            // connection is successful
            connectSelectorLoop.register(false, true, false, false, session, clientSocket, new RegistrationCallback() {

                @Override
                public void done(SelectionKey selectionKey) {
                    session.setSelectionKey(selectionKey);
                }
            });
        } else {
            // already connected (probably a loopback connection)
            // register for read
            connectSelectorLoop.register(false, false, true, false, session, clientSocket, new RegistrationCallback() {

                @Override
                public void done(SelectionKey selectionKey) {
                    session.setSelectionKey(selectionKey);
                }
            });
            session.setConnected();
        }
        return connectFuture;
    }

}