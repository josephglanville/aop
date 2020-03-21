/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.streamnative.pulsar.handlers.amqp;

import static java.nio.charset.StandardCharsets.US_ASCII;

import com.google.common.annotations.VisibleForTesting;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.log4j.Log4j2;
import org.apache.bookkeeper.util.collections.ConcurrentLongLongHashMap;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.util.collections.ConcurrentLongHashMap;
import org.apache.qpid.server.QpidException;
import org.apache.qpid.server.protocol.ErrorCodes;
import org.apache.qpid.server.protocol.ProtocolVersion;
import org.apache.qpid.server.protocol.v0_8.AMQShortString;
import org.apache.qpid.server.protocol.v0_8.FieldTable;
import org.apache.qpid.server.protocol.v0_8.transport.AMQDataBlock;
import org.apache.qpid.server.protocol.v0_8.transport.AMQFrame;
import org.apache.qpid.server.protocol.v0_8.transport.AMQMethodBody;
import org.apache.qpid.server.protocol.v0_8.transport.ChannelOpenOkBody;
import org.apache.qpid.server.protocol.v0_8.transport.ConnectionCloseBody;
import org.apache.qpid.server.protocol.v0_8.transport.ConnectionCloseOkBody;
import org.apache.qpid.server.protocol.v0_8.transport.ConnectionTuneBody;
import org.apache.qpid.server.protocol.v0_8.transport.HeartbeatBody;
import org.apache.qpid.server.protocol.v0_8.transport.MethodRegistry;
import org.apache.qpid.server.protocol.v0_8.transport.ProtocolInitiation;
import org.apache.qpid.server.protocol.v0_8.transport.ServerChannelMethodProcessor;
import org.apache.qpid.server.protocol.v0_8.transport.ServerMethodProcessor;
import org.apache.qpid.server.transport.ByteBufferSender;

/**
 * Amqp server level method processor.
 */
@Log4j2
public class AmqpConnection extends AmqpCommandDecoder implements ServerMethodProcessor<ServerChannelMethodProcessor> {

    enum ConnectionState {
        INIT,
        AWAIT_START_OK,
        AWAIT_SECURE_OK,
        AWAIT_TUNE_OK,
        AWAIT_OPEN,
        OPEN
    }

    private final ConcurrentLongHashMap<AmqpChannel> channels;
    private final ConcurrentLongLongHashMap closingChannelsList = new ConcurrentLongLongHashMap();
    private final AmqpServiceConfiguration amqpConfig;
    private ProtocolVersion protocolVersion;
    private MethodRegistry methodRegistry;
    private ByteBufferSender bufferSender;
    private volatile ConnectionState state = ConnectionState.INIT;
    private volatile int currentClassId;
    private volatile int currentMethodId;
    private final AtomicBoolean orderlyClose = new AtomicBoolean(false);
    private volatile int maxChannels;
    private volatile int maxFrameSize;
    private volatile int heartBeat;
    private NamespaceName namespaceName;
    private final Object channelAddRemoveLock = new Object();
    private AtomicBoolean blocked = new AtomicBoolean();

    public AmqpConnection(PulsarService pulsarService, AmqpServiceConfiguration amqpConfig) {
        super(pulsarService, amqpConfig);
        this.channels = new ConcurrentLongHashMap<>();
        this.protocolVersion = ProtocolVersion.v0_91;
        this.methodRegistry = new MethodRegistry(this.protocolVersion);
        this.bufferSender = new AmqpByteBufferSender(this);
        this.amqpConfig = amqpConfig;
        this.maxChannels = amqpConfig.getMaxNoOfChannels();
        this.maxFrameSize = amqpConfig.getMaxFrameSize();
        this.heartBeat = amqpConfig.getHeartBeat();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        this.remoteAddress = ctx.channel().remoteAddress();
        this.ctx = ctx;
        isActive.set(true);
        this.brokerDecoder = new AmqpBrokerDecoder(this);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("[{}] Got exception: {}", remoteAddress, cause.getMessage(), cause);
        close();
    }

    @Override
    protected void close() {
        if (isActive.getAndSet(false)) {
            log.info("close netty channel {}", ctx.channel());
            ctx.close();
        }
    }

    @Override
    public void receiveConnectionStartOk(FieldTable clientProperties, AMQShortString mechanism, byte[] response,
        AMQShortString locale) {
        if (log.isDebugEnabled()) {
            log.debug("RECV ConnectionStartOk[clientProperties: {}, mechanism: {}, locale: {}]",
                clientProperties, mechanism, locale);
        }
        assertState(ConnectionState.AWAIT_START_OK);
        // TODO clientProperties
        AMQMethodBody responseBody = this.methodRegistry.createConnectionSecureBody(new byte[0]);
        writeFrame(responseBody.generateFrame(0));
        state = ConnectionState.AWAIT_SECURE_OK;
        bufferSender.flush();
    }

    @Override
    public void receiveConnectionSecureOk(byte[] response) {
        if (log.isDebugEnabled()) {
            log.debug("RECV ConnectionSecureOk");
        }
        assertState(ConnectionState.AWAIT_SECURE_OK);
        // TODO AUTH
        ConnectionTuneBody tuneBody =
            methodRegistry.createConnectionTuneBody(maxChannels,
                maxFrameSize,
                heartBeat);
        writeFrame(tuneBody.generateFrame(0));
        state = ConnectionState.AWAIT_TUNE_OK;
    }

    @Override
    public void receiveConnectionTuneOk(int channelMax, long frameMax, int heartbeat) {
        if (log.isDebugEnabled()) {
            log.debug("RECV ConnectionTuneOk[ channelMax: {} frameMax: {} heartbeat: {} ]",
                channelMax, frameMax, heartbeat);
        }
        assertState(ConnectionState.AWAIT_TUNE_OK);

        if (heartbeat > 0) {
            this.heartBeat = heartbeat;
            long writerDelay = 1000L * heartbeat;
            long readerDelay = 1000L * 2 * heartbeat;
            initHeartBeatHandler(writerDelay, readerDelay);
        }
        int brokerFrameMax = maxFrameSize;
        if (brokerFrameMax <= 0) {
            brokerFrameMax = Integer.MAX_VALUE;
        }

        if (frameMax > (long) brokerFrameMax) {
            sendConnectionClose(ErrorCodes.SYNTAX_ERROR,
                "Attempt to set max frame size to " + frameMax
                    + " greater than the broker will allow: "
                    + brokerFrameMax, 0);
        } else if (frameMax > 0 && frameMax < AMQFrame.getFrameOverhead()) {
            sendConnectionClose(ErrorCodes.SYNTAX_ERROR,
                "Attempt to set max frame size to " + frameMax
                    + " which is smaller than the specification defined minimum: "
                    + AMQFrame.getFrameOverhead(), 0);
        } else {
            int calculatedFrameMax = frameMax == 0 ? brokerFrameMax : (int) frameMax;
            setMaxFrameSize(calculatedFrameMax);

            //0 means no implied limit, except that forced by protocol limitations (0xFFFF)
            int value = ((channelMax == 0) || (channelMax > 0xFFFF))
                ? 0xFFFF
                : channelMax;
            maxChannels = value;
        }
        state = ConnectionState.AWAIT_OPEN;

    }

    @Override
    public void receiveConnectionOpen(AMQShortString virtualHost, AMQShortString capabilities, boolean insist) {
        if (log.isDebugEnabled()) {
            log.debug("RECV ConnectionOpen[virtualHost: {} capabilities: {} insist: {} ]",
                virtualHost, capabilities, insist);
        }

        assertState(ConnectionState.AWAIT_OPEN);

        String virtualHostStr = AMQShortString.toString(virtualHost);
        if ((virtualHostStr != null) && virtualHostStr.charAt(0) == '/') {
            virtualHostStr = virtualHostStr.substring(1);
        }

        NamespaceName namespaceName = NamespaceName.get(amqpConfig.getAmqpTenant(), virtualHostStr);
        // Policies policies = getPolicies(namespaceName);
//        if (policies != null) {
        this.namespaceName = namespaceName;

        MethodRegistry methodRegistry = getMethodRegistry();
        AMQMethodBody responseBody = methodRegistry.createConnectionOpenOkBody(virtualHost);
        writeFrame(responseBody.generateFrame(0));
        state = ConnectionState.OPEN;
//        } else {
//            sendConnectionClose(ErrorCodes.NOT_FOUND,
//                "Unknown virtual host: '" + virtualHostStr + "'", 0);
//        }
    }

    @Override
    public void receiveConnectionClose(int replyCode, AMQShortString replyText,
        int classId, int methodId) {
        if (log.isDebugEnabled()) {
            log.debug("RECV ConnectionClose[ replyCode: {} replyText: {} classId: {} methodId: {} ]",
                replyCode, replyText, classId, methodId);
        }

        try {
            if (orderlyClose.compareAndSet(false, true)) {
                completeAndCloseAllChannels();
            }

            MethodRegistry methodRegistry = getMethodRegistry();
            ConnectionCloseOkBody responseBody = methodRegistry.createConnectionCloseOkBody();
            writeFrame(responseBody.generateFrame(0));
        } catch (Exception e) {
            log.error("Error closing connection for " + this.remoteAddress.toString(), e);
        } finally {
            close();
        }
    }

    @Override
    public void receiveConnectionCloseOk() {
        if (log.isDebugEnabled()) {
            log.debug("RECV ConnectionCloseOk");
        }
        close();
    }

    public void sendConnectionClose(int errorCode, String message, int channelId) {
        sendConnectionClose(channelId, new AMQFrame(0, new ConnectionCloseBody(getProtocolVersion(),
            errorCode, AMQShortString.validValueOf(message), currentClassId, currentMethodId)));
    }

    private void sendConnectionClose(int channelId, AMQFrame frame) {
        if (orderlyClose.compareAndSet(false, true)) {
            try {
                markChannelAwaitingCloseOk(channelId);
                completeAndCloseAllChannels();
            } finally {
                writeFrame(frame);
            }
        }
    }

    @Override
    public void receiveChannelOpen(int channelId) {

        if (log.isDebugEnabled()) {
            log.debug("RECV[" + channelId + "] ChannelOpen");
        }
        assertState(ConnectionState.OPEN);

        if (this.namespaceName == null) {
            sendConnectionClose(ErrorCodes.COMMAND_INVALID,
                "Virtualhost has not yet been set. ConnectionOpen has not been called.", channelId);
        } else if (channels.get(channelId) != null || channelAwaitingClosure(channelId)) {
            sendConnectionClose(ErrorCodes.CHANNEL_ERROR, "Channel " + channelId + " already exists", channelId);
        } else if (channelId > maxChannels) {
            sendConnectionClose(ErrorCodes.CHANNEL_ERROR,
                "Channel " + channelId + " cannot be created as the max allowed channel id is "
                    + maxChannels,
                channelId);
        } else {
            log.debug("Connecting to: {}", namespaceName.getLocalName());
            final AmqpChannel channel = new AmqpChannel(channelId, this);
            addChannel(channel);

            ChannelOpenOkBody response = getMethodRegistry().createChannelOpenOkBody();
            writeFrame(response.generateFrame(channelId));
        }

    }

    private void addChannel(AmqpChannel channel) {
        synchronized (channelAddRemoveLock) {
            channels.put(channel.getChannelId(), channel);
            if (blocked.get()) {
                channel.block();
            }
        }
    }

    @Override
    public void receiveHeartbeat() {
        if (log.isDebugEnabled()) {
            log.debug("RECV Heartbeat");
        }
        // noop
    }

    @Override
    public void receiveProtocolHeader(ProtocolInitiation pi) {
        if (log.isDebugEnabled()) {
            log.debug("RECV Protocol Header [{}]", pi);
        }
        brokerDecoder.setExpectProtocolInitiation(false);
        try {
            ProtocolVersion pv = pi.checkVersion(); // Fails if not correct
            // TODO serverProperties mechanis
            AMQMethodBody responseBody = this.methodRegistry.createConnectionStartBody(
                (short) protocolVersion.getMajorVersion(),
                (short) pv.getActualMinorVersion(),
                null,
                // TODO temporary modification
                "PLAIN".getBytes(US_ASCII),
                "en_US".getBytes(US_ASCII));
            writeFrame(responseBody.generateFrame(0));
            state = ConnectionState.AWAIT_START_OK;
            bufferSender.flush();
        } catch (QpidException e) {
            log.error("Received unsupported protocol initiation for protocol version: {} ", getProtocolVersion(), e);
        }
    }

    @Override
    public ProtocolVersion getProtocolVersion() {
        return this.protocolVersion;
    }

    @Override
    public ServerChannelMethodProcessor getChannelMethodProcessor(int channelId) {
        return this.channels.get(channelId);
    }

    @Override
    public void setCurrentMethod(int classId, int methodId) {
        currentClassId = classId;
        currentMethodId = methodId;
    }

    void assertState(final ConnectionState requiredState) {
        if (state != requiredState) {
            String replyText = "Command Invalid, expected " + requiredState + " but was " + state;
            sendConnectionClose(ErrorCodes.COMMAND_INVALID, replyText, 0);
            throw new RuntimeException(replyText);
        }
    }

    public boolean channelAwaitingClosure(int channelId) {
        return ignoreAllButCloseOk() || (!closingChannelsList.isEmpty()
            && closingChannelsList.containsKey(channelId));
    }

    private void completeAndCloseAllChannels() {
        try {
            receivedCompleteAllChannels();
        } finally {
            closeAllChannels();
        }
    }

    private void receivedCompleteAllChannels() {
        RuntimeException exception = null;

        for (AmqpChannel channel : channels.values()) {
            try {
                channel.receivedComplete();
            } catch (RuntimeException exceptionForThisChannel) {
                if (exception == null) {
                    exception = exceptionForThisChannel;
                }
                log.error("error informing channel that receiving is complete. Channel: " + channel,
                    exceptionForThisChannel);
            }
        }

        if (exception != null) {
            throw exception;
        }
    }

    public synchronized void writeFrame(AMQDataBlock frame) {
        if (log.isDebugEnabled()) {
            log.debug("send: " + frame);
        }

        frame.writePayload(bufferSender);
        bufferSender.flush();
    }

    public MethodRegistry getMethodRegistry() {
        return methodRegistry;
    }

    @VisibleForTesting
    public void setBufferSender(ByteBufferSender sender) {
        this.bufferSender = sender;
    }

    @VisibleForTesting
    public AmqpServiceConfiguration getAmqpConfig() {
        return amqpConfig;
    }

    @VisibleForTesting
    public void setMaxChannels(int maxChannels) {
        this.maxChannels = maxChannels;
    }

    @VisibleForTesting
    public void setHeartBeat(int heartBeat) {
        this.heartBeat = heartBeat;
    }

    public void initHeartBeatHandler(long writerIdle, long readerIdle) {

        this.ctx.pipeline().addFirst("idleStateHandler", new IdleStateHandler(readerIdle, writerIdle, 0,
            TimeUnit.MILLISECONDS));
        this.ctx.pipeline().addLast("connectionIdleHandler", new ConnectionIdleHandler());

    }

    class ConnectionIdleHandler extends ChannelDuplexHandler {

        @Override public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof IdleStateEvent) {
                IdleStateEvent event = (IdleStateEvent) evt;
                if (event.state().equals(IdleState.READER_IDLE)) {
                    log.error("heartbeat timeout close remoteSocketAddress [{}]",
                        AmqpConnection.this.remoteAddress.toString());
                    AmqpConnection.this.close();
                } else if (event.state().equals(IdleState.WRITER_IDLE)) {
                    log.warn("heartbeat write  idle [{}]", AmqpConnection.this.remoteAddress.toString());
                    writeFrame(HeartbeatBody.FRAME);
                }
            }

            super.userEventTriggered(ctx, evt);
        }

    }

    public void setMaxFrameSize(int frameMax) {
        maxFrameSize = frameMax;
        brokerDecoder.setMaxFrameSize(frameMax);
    }

    public AmqpChannel getChannel(int channelId) {
        final AmqpChannel channel = channels.get(channelId);
        if ((channel == null) || channel.isClosing()) {
            return null;
        } else {
            return channel;
        }
    }

    public boolean isClosing() {
        return orderlyClose.get();
    }

    @Override
    public boolean ignoreAllButCloseOk() {
        return isClosing();
    }

    public void closeChannelOk(int channelId) {
        closingChannelsList.remove(channelId);
    }

    private void markChannelAwaitingCloseOk(int channelId) {
        closingChannelsList.put(channelId, System.currentTimeMillis());
    }

    private void removeChannel(int channelId) {
        synchronized (channelAddRemoveLock) {
            channels.remove(channelId);
        }
    }

    public void closeChannel(AmqpChannel channel) {
        closeChannel(channel, false);
    }

    public void closeChannelAndWriteFrame(AmqpChannel channel, int cause, String message) {
        writeFrame(new AMQFrame(channel.getChannelId(),
            getMethodRegistry().createChannelCloseBody(cause,
                AMQShortString.validValueOf(message),
                currentClassId,
                currentMethodId)));
        closeChannel(channel, true);
    }

    void closeChannel(AmqpChannel channel, boolean mark) {
        int channelId = channel.getChannelId();
        try {
            channel.close();
            if (mark) {
                markChannelAwaitingCloseOk(channelId);
            }
        } finally {
            removeChannel(channelId);
        }
    }

    private void closeAllChannels() {
        RuntimeException exception = null;
        try {
            for (AmqpChannel channel : channels.values()) {
                try {
                    channel.close();
                } catch (RuntimeException exceptionForThisChannel) {
                    if (exception == null) {
                        exception = exceptionForThisChannel;
                    }
                    log.error("error informing channel that receiving is complete. Channel: " + channel,
                        exceptionForThisChannel);
                }
            }
            if (exception != null) {
                throw exception;
            }
        } finally {
            synchronized (channelAddRemoveLock) {
                channels.clear();
            }
        }
    }

    public void block() {
        synchronized (channelAddRemoveLock) {
            if (blocked.compareAndSet(false, true)) {
                for (AmqpChannel channel : channels.values()) {
                    channel.block();
                }
            }
        }
    }

//    public Policies getPolicies(NamespaceName namespaceName) {
//        return getPulsarService().getConfigurationCache().policiesCache()
//            .get(AdminResource.path(POLICIES, namespaceName.toString())).orElse(null);

    public int getMaxChannels () {
        return maxChannels;
    }

    public int getMaxFrameSize () {
        return maxFrameSize;
    }

    public int getHeartBeat () {
        return heartBeat;
    }

}