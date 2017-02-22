/**
 * Copyright 2016-2017 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.reaktivity.nukleus.tcp.internal.reader.stream;

import static java.nio.ByteOrder.nativeOrder;

import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.function.IntSupplier;
import java.util.function.LongFunction;

import org.agrona.DirectBuffer;
import org.agrona.LangUtil;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.reaktivity.nukleus.tcp.internal.reader.Target;
import org.reaktivity.nukleus.tcp.internal.router.Correlation;
import org.reaktivity.nukleus.tcp.internal.types.stream.ResetFW;
import org.reaktivity.nukleus.tcp.internal.types.stream.WindowFW;

public final class StreamFactory
{
    private final WindowFW windowRO = new WindowFW();
    private final ResetFW resetRO = new ResetFW();

    private final int bufferSize;
    private final LongFunction<Correlation> resolveCorrelation;
    private final ByteBuffer readBuffer;
    private final AtomicBuffer atomicBuffer;

    public StreamFactory(
        int bufferSize,
        LongFunction<Correlation> resolveCorrelation)
    {
        this.bufferSize = bufferSize;
        this.resolveCorrelation = resolveCorrelation;
        this.readBuffer = ByteBuffer.allocate(bufferSize).order(nativeOrder());
        this.atomicBuffer = new UnsafeBuffer(new byte[bufferSize]);
    }

    public IntSupplier newStream(
        Target target,
        long targetId,
        SelectionKey key,
        SocketChannel channel,
        long correlationId)
    {
        final Stream stream = new Stream(target, targetId, key, channel, correlationId);

        target.addThrottle(targetId, stream::handleThrottle);

        return stream::handleStream;
    }

    private final class Stream
    {
        private final Target target;
        private final long streamId;
        private final SelectionKey key;
        private final SocketChannel channel;
        private final long correlationId;

        private int readableBytes;

        private Stream(
            Target target,
            long streamId,
            SelectionKey key,
            SocketChannel channel,
            long correlationId)
        {
            this.target = target;
            this.streamId = streamId;
            this.key = key;
            this.channel = channel;
            this.correlationId = correlationId;
        }

        private int handleStream()
        {
            return handleRead();
        }

        private int handleRead()
        {
            assert readableBytes > 0;

            final int limit = Math.min(readableBytes, bufferSize);

            readBuffer.position(0);
            readBuffer.limit(limit);

            int bytesRead;
            try
            {
                bytesRead = channel.read(readBuffer);
            }
            catch(IOException ex)
            {
                // RST from client. We treat this as end of stream (see issue #9).
                bytesRead = -1;
            }
            if (bytesRead == -1)
            {
                // channel closed
                target.doTcpEnd(streamId);
                target.removeThrottle(streamId);
                key.cancel();
            }
            else
            {
                // TODO: eliminate copy
                atomicBuffer.putBytes(0, readBuffer, 0, bytesRead);

                target.doTcpData(streamId, atomicBuffer, 0, bytesRead);

                readableBytes -= bytesRead;
                if (readableBytes == 0)
                {
                    final int interestOps = key.interestOps();
                    final int newInterestOps = interestOps & ~SelectionKey.OP_READ;
                    key.interestOps(newInterestOps);
                }
            }

            return 1;
        }

        private void handleThrottle(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case WindowFW.TYPE_ID:
                processWindow(buffer, index, length);
                break;
            case ResetFW.TYPE_ID:
                processReset(buffer, index, length);
                break;
            default:
                // ignore
                break;
            }
        }

        private void processWindow(
            DirectBuffer buffer,
            int index,
            int length)
        {
            windowRO.wrap(buffer, index, index + length);

            final int update = windowRO.update();
            if (readableBytes == 0 && update > 0)
            {
                final int interestOps = key.interestOps();
                final int newInterestOps = interestOps | SelectionKey.OP_READ;
                key.interestOps(newInterestOps);
            }

            readableBytes += update;
        }

        private void processReset(
            DirectBuffer buffer,
            int index,
            int length)
        {
            resetRO.wrap(buffer, index, index + length);

            try
            {
                if (resolveCorrelation.apply(correlationId) == null)
                {
                    // Begin on correlated output stream was already processed
                    channel.shutdownInput();
                }
                else
                {
                    // Force a hard reset (TCP RST), as documented in "Orderly Versus Abortive Connection Release in Java"
                    // (https://docs.oracle.com/javase/8/docs/technotes/guides/net/articles/connection_release.html)
                    channel.setOption(StandardSocketOptions.SO_LINGER, 0);
                    channel.close();
                }
            }
            catch (IOException ex)
            {
                LangUtil.rethrowUnchecked(ex);
            }
            finally
            {
                target.removeThrottle(streamId);
            }
        }
    }
}
