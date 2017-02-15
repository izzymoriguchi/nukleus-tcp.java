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
package org.reaktivity.nukleus.tcp.internal.streams;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.rules.RuleChain.outerRule;
import static org.reaktivity.nukleus.tcp.internal.InternalSystemProperty.MAXIMUM_STREAMS_WITH_PENDING_WRITES;
import static org.reaktivity.nukleus.tcp.internal.InternalSystemProperty.WINDOW_SIZE;

import java.io.InputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.byteman.contrib.bmunit.BMScript;
import org.jboss.byteman.contrib.bmunit.BMUnitConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.kaazing.k3po.junit.annotation.Specification;
import org.kaazing.k3po.junit.rules.K3poRule;
import org.reaktivity.nukleus.tcp.internal.TcpCountersRule;
import org.reaktivity.reaktor.test.NukleusRule;

/**
 * Tests the handling of capacity exceeded conditions in the context of incomplete writes
 */
@RunWith(org.jboss.byteman.contrib.bmunit.BMUnitRunner.class)
public class ServerPartialWriteLimitsIT
{
    private final K3poRule k3po = new K3poRule()
        .addScriptRoot("route", "org/reaktivity/specification/nukleus/tcp/control/route")
        .addScriptRoot("streams", "org/reaktivity/specification/nukleus/tcp/streams");

    private final TestRule timeout = new DisableOnDebug(new Timeout(5, SECONDS));

    private final NukleusRule nukleus = new NukleusRule("tcp")
        .directory("target/nukleus-itests")
        .commandBufferCapacity(1024)
        .responseBufferCapacity(1024)
        .counterValuesBufferCapacity(1024)
        .streams("tcp", "target#partition");

    private final TestRule properties = new SystemPropertiesRule()
            .setProperty(MAXIMUM_STREAMS_WITH_PENDING_WRITES.propertyName(), "1")
            .setProperty(WINDOW_SIZE.propertyName(), "15");

    private final TcpCountersRule tcpCounters = new TcpCountersRule()
            .directory("target/nukleus-itests")
            .commandBufferCapacity(1024)
            .responseBufferCapacity(1024)
            .counterValuesBufferCapacity(1024);

    @Rule
    public final TestRule chain = outerRule(PartialWriteHelper.RULE).around(properties)
                                  .around(nukleus).around(tcpCounters).around(k3po).around(timeout);

    @Test
    @Specification({
        "${route}/input/new/controller",
        "${streams}/server.sent.data.multiple.frames.partial.writes/server/target"
    })
    @BMUnitConfig(loadDirectory="src/test/resources", debug=false, verbose=false)
    @BMScript(value="PartialWriteIT.btm")
    public void shouldWriteWhenMoreDataArrivesWhileAwaitingSocketWritableWithoutOverflowingSlot() throws Exception
    {
        PartialWriteHelper.addWriteResult(5);
        PartialWriteHelper.addWriteResult(6);
        AtomicBoolean allDataWritten = new AtomicBoolean(false);
        PartialWriteHelper.setWriteResultProvider((caller) -> allDataWritten.get() ? null : 0);

        k3po.start();
        k3po.awaitBarrier("ROUTED_INPUT");

        try (Socket socket = new Socket("127.0.0.1", 0x1f90))
        {
            final InputStream in = socket.getInputStream();

            k3po.awaitBarrier("SECOND_WRITE_COMPLETED");
            allDataWritten.set(true);

            String expectedData = "server data 1server data 2";
            byte[] buf = new byte[expectedData.length() + 10];
            int offset = 0;

            int read = 0;
            boolean closed = false;
            do
            {
                read = in.read(buf, offset, buf.length - offset);
                if (read == -1)
                {
                    closed = true;
                    break;
                }
                offset += read;
            } while (offset < expectedData.length());
            assertFalse(closed);
            assertEquals(expectedData, new String(buf, 0, offset, UTF_8));

            k3po.finish();
        }
        assertEquals(1, tcpCounters.counters().streams().get());
        assertEquals(1, tcpCounters.counters().routes().get());
        assertEquals(0, tcpCounters.counters().streamsOverflowed().get());
    }

    @Test
    @Specification({
        "${route}/input/new/controller",
        "${streams}/server.sent.data.multiple.streams.second.was.reset/server/target"
    })
    @BMUnitConfig(loadDirectory="src/test/resources", debug=false, verbose=false)
    @BMScript(value="PartialWriteIT.btm")
    public void shouldResetStreamsExceedingPartialWriteStreamsLimit() throws Exception
    {
        PartialWriteHelper.addWriteResult(1); // avoid spin write for first stream write
        AtomicBoolean resetReceived = new AtomicBoolean(false);
        PartialWriteHelper.setWriteResultProvider((caller) -> resetReceived.get() ? null : 0);

        k3po.start();
        k3po.awaitBarrier("ROUTED_INPUT");

        try (Socket socket = new Socket("127.0.0.1", 0x1f90);
             Socket socket2 = new Socket("127.0.0.1", 0x1f90))
        {
            k3po.awaitBarrier("SECOND_STREAM_RESET_RECEIVED");
            resetReceived.set(true);

            InputStream in = socket.getInputStream();
            byte[] buf = new byte[256];
            int offset = 0;
            while (offset < 13)
            {
                int len = in.read(buf, offset, buf.length - offset);
                if (len == -1)
                {
                    break;
                }
                offset += len;
            }
            assertEquals("server data 1", new String(buf, 0, offset, UTF_8));

            in = socket2.getInputStream();
            offset = 0;
            int len = 0;
            while (offset < 13)
            {
                len = in.read(buf, offset, buf.length - offset);
                if (len == -1)
                {
                    break;
                }
                offset += len;
            }
            assertEquals(-1, len);
        }

        k3po.finish();
        assertEquals(2, tcpCounters.counters().streams().get());
        assertEquals(1, tcpCounters.counters().routes().get());
        assertEquals(1, tcpCounters.counters().streamsOverflowed().get());
    }

}