/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
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

package tmp.texugo.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Deque;

import org.xnio.ChannelListener;
import org.xnio.IoUtils;
import org.xnio.Pool;
import org.xnio.channels.AssembledConnectedStreamChannel;
import org.xnio.channels.ConnectedStreamChannel;
import org.xnio.channels.StreamSinkChannel;
import org.xnio.channels.StreamSourceChannel;
import tmp.texugo.TexugoMessages;
import tmp.texugo.util.AbstractAttachable;
import tmp.texugo.util.HeaderMap;
import tmp.texugo.util.Protocols;
import tmp.texugo.util.StatusCodes;

/**
 * An HTTP server request/response exchange.  An instance of this class is constructed as soon as the request headers are
 * fully parsed.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public final class HttpServerExchange extends AbstractAttachable {
    private final Pool<ByteBuffer> bufferPool;
    private final HttpServerConnection connection;
    private HeaderMap requestHeaders;
    private HeaderMap responseHeaders;
    private int responseCode = 200;
    private String requestMethod;
    private String protocol;
    private String requestScheme;
    /**
     * The original request path.
     */
    private String requestPath;
    /**
     * The canonical version of the original path.
     */
    private String canonicalPath;
    /**
     * The remaining unresolved portion of the canonical path.
     */
    private String relativePath;
    private StreamSourceChannel requestChannel;
    private StreamSinkChannel responseChannel;

    /**
     * Set to true once the response headers etc have been written out
     */
    private boolean responseStarted = false;

    protected HttpServerExchange(final Pool<ByteBuffer> bufferPool, final HttpServerConnection connection, final HeaderMap requestHeaders, final HeaderMap responseHeaders, final String requestMethod) {
        this.bufferPool = bufferPool;
        this.connection = connection;
        this.requestHeaders = requestHeaders;
        this.responseHeaders = responseHeaders;
        this.requestMethod = requestMethod;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(final String protocol) {
        this.protocol = protocol;
    }

    public boolean isHttp09() {
        return protocol.equals(Protocols.HTTP_0_9);
    }

    public boolean isHttp10() {
        return protocol.equals(Protocols.HTTP_1_0);
    }

    public boolean isHttp11() {
        return protocol.equals(Protocols.HTTP_1_1);
    }

    public String getRequestMethod() {
        return requestMethod;
    }

    public void setRequestMethod(final String requestMethod) {
        this.requestMethod = requestMethod;
    }

    public String getRequestScheme() {
        return requestScheme;
    }

    public void setRequestScheme(final String requestScheme) {
        this.requestScheme = requestScheme;
    }

    public String getRequestPath() {
        return requestPath;
    }

    public void setRequestPath(final String requestPath) {
        this.requestPath = requestPath;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(final String relativePath) {
        this.relativePath = relativePath;
    }

    public String getCanonicalPath() {
        return canonicalPath;
    }

    public void setCanonicalPath(final String canonicalPath) {
        this.canonicalPath = canonicalPath;
    }

    public HttpServerConnection getConnection() {
        return connection;
    }

    /**
     * Upgrade the channel to a raw socket.  This is a convenience method which sets a 101 response code, sends the
     * response headers, and merges the request and response channels into one full-duplex socket stream channel.
     *
     * @return the socket channel
     * @throws IllegalStateException if a response or upgrade was already sent, or if the request body is already being
     *                               read
     */
    public ConnectedStreamChannel upgradeChannel() throws IllegalStateException, IOException {
        setResponseCode(101);
        startResponse();
        return new AssembledConnectedStreamChannel(getRequestChannel(), getResponseChannel());
    }

    /**
     * Get the source address of the HTTP request.
     *
     * @return the source address of the HTTP request
     */
    public InetSocketAddress getSourceAddress() {
        return connection.getPeerAddress(InetSocketAddress.class);
    }

    /**
     * Get the destination address of the HTTP request.
     *
     * @return the destination address of the HTTP request
     */
    public InetSocketAddress getDestinationAddress() {
        return connection.getLocalAddress(InetSocketAddress.class);
    }

    /**
     * Get the request headers.
     *
     * @return the request headers
     */
    public HeaderMap getRequestHeaders() {
        return requestHeaders;
    }

    /**
     * Get the response headers.
     *
     * @return the response headers
     */
    public HeaderMap getResponseHeaders() {
        return responseHeaders;
    }

    /**
     * @return <code>true</code> If the response has already been started
     */
    public boolean isResponseStarted() {
        return responseStarted;
    }

    /**
     * Get the inbound request.  If there is no request body, calling this method
     * may cause the next request to immediately be processed.  The {@link StreamSourceChannel#close()} or {@link StreamSourceChannel#shutdownReads()}
     * method must be called at some point after the request is processed to prevent resource leakage and to allow
     * the next request to proceed.  Any unread content will be discarded.  Multiple calls to this method will return
     * the same channel.
     *
     * @return the channel for the inbound request
     */
    public StreamSourceChannel getRequestChannel() {
        return requestChannel;
    }

    /**
     * Change the request channel.  Subsequent calls to {@link #getRequestChannel()} will return the replaced channel.
     * If a channel is replaced, it is the responsibility of the replacing party to ensure that the request is read
     * in full and closed.  This may be done by wrapping the original channel or by handling the request body
     * asynchronously.
     *
     * @param requestChannel the replacement channel
     */
    public void setRequestChannel(final StreamSourceChannel requestChannel) {
        this.requestChannel = requestChannel;
    }

    /**
     * Force the codec to treat the request as fully read.  Should only be invoked by handlers which downgrade
     * the socket or implement a transfer coding.
     */
    void terminateRequest() {
    }

    /**
     * Get the response channel. The {@link StreamSinkChannel#close()} or {@link StreamSinkChannel#shutdownWrites()}
     * method must be called at some point after the request is processed to prevent resource leakage and to allow
     * the next request to proceed.  Closing a fixed-length response before the corresponding number of bytes has
     * been written will cause the connection to be reset and subsequent requests to fail; thus it is important to
     * ensure that the proper content length is delivered when one is specified.  Multiple calls to this method will
     * return the same channel.  The response channel may not be writable until after the response headers have been
     * sent.
     *
     * @return the response channel
     */
    public StreamSinkChannel getResponseChannel() {
        return responseChannel;
    }

    /**
     * Change the response channel.  Subsequent calls to {@link #getResponseChannel()} will return the replaced channel.
     * If a channel is replaced, it is the responsibility of the replacing party to ensure that the response is written
     * in full and closed.  This may be done by wrapping the original channel or by writing the response body
     * asynchronously.
     *
     * @param responseChannel the replacement channel
     */
    public void setResponseChannel(final StreamSinkChannel responseChannel) {
        this.responseChannel = responseChannel;
    }

    /**
     * Change the response code for this response.  If not specified, the code will be a {@code 200}.  Setting
     * the response code after the response headers have been transmitted has no effect.
     *
     * @param responseCode the new code
     * @throws IllegalStateException if a response or upgrade was already sent
     */
    public void setResponseCode(final int responseCode) {
        if (responseStarted) {
            throw TexugoMessages.MESSAGES.responseAlreadyStarted();
        }
        this.responseCode = responseCode;
    }

    /**
     * Get the response code.
     *
     * @return the response code
     */
    public int getResponseCode() {
        return responseCode;
    }

    /**
     * Force the codec to treat the response as fully written.  Should only be invoked by handlers which downgrade
     * the socket or implement a transfer coding.
     */
    void terminateResponse() {
        IoUtils.safeClose(responseChannel);
        IoUtils.safeClose(requestChannel);
    }

    /**
     * Transmit the response headers. After this method successfully returns,
     * the response channel may become writable.
     * <p/>
     * If this method fails the request and response channels will be closed.
     * <p/>
     * This method runs asynchronously. If the channel is writable it will
     * attempt to write as much of the response header as possible, and then
     * queue the rest in a listener and return.
     * <p/>
     * If future handlers in the chain attempt to write before this is finished
     * XNIO will just magically sort it out so it works. This is not actually
     * implemented yet, so we just terminate the connection straight away at
     * the moment.
     * <p/>
     * TODO: make this work properly
     *
     * @throws IllegalStateException if the response headers were already sent
     */
    public void startResponse() throws IllegalStateException {
        if (responseStarted) {
            TexugoMessages.MESSAGES.responseAlreadyStarted();
        }
        final HeaderMap responseHeaders = this.responseHeaders;
        responseHeaders.lock();
        responseStarted = true;

        try {
            //TODO: we should not be using a StringBuilder here, we should be wiring this stuff directly into a buffer
            final StringBuilder response = new StringBuilder(protocol);
            response.append(' ');
            final int responseCode = this.responseCode;
            response.append(responseCode);
            response.append(' ');
            response.append(StatusCodes.getReason(responseCode));
            response.append("\r\n");
            for (final String header : responseHeaders) {
                response.append(header);
                response.append(": ");
                final Deque<String> values = responseHeaders.get(header);
                for (String value : values) {
                    response.append(value);
                    response.append(' ');
                }
                response.append("\r\n");
            }
            response.append("\r\n");

            final String result = response.toString();
            ResponseWriteListener responseWriteListener = new ResponseWriteListener(ByteBuffer.wrap(result.getBytes()), result.length(), this );
            responseChannel.getWriteSetter().set(responseWriteListener);
            responseChannel.resumeWrites();
        } catch (RuntimeException e) {
            IoUtils.safeClose(requestChannel);
            IoUtils.safeClose(requestChannel);
            throw e;
        }
    }

    private static class ResponseWriteListener implements ChannelListener<StreamSinkChannel> {

        private final ByteBuffer buffer;
        private int remaining;
        private final HttpServerExchange exchange;

        private ResponseWriteListener(final ByteBuffer buffer, final int remaining, final HttpServerExchange exchange) {
            this.buffer = buffer;
            this.remaining = remaining;
            this.exchange = exchange;
        }

        @Override
        public void handleEvent(final StreamSinkChannel channel) {
            try {
                int c = channel.write(buffer);
                remaining = remaining - c;
                if (remaining > 0) {
                    channel.resumeWrites();
                } else {
                    //TODO: FIX THIS
                    exchange.terminateResponse();
                }
            } catch (IOException e) {
                //TODO: we need some consistent way of handling IO exception
                IoUtils.safeClose(channel);
                IoUtils.safeClose(exchange.getRequestChannel());
            }
        }

        public int getRemaining() {
            return remaining;
        }
    }
}