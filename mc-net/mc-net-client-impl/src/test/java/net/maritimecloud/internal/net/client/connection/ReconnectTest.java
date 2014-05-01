/* Copyright (c) 2011 Danish Maritime Authority.
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
package net.maritimecloud.internal.net.client.connection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import net.maritimecloud.internal.net.client.AbstractClientConnectionTest;
import net.maritimecloud.internal.net.client.broadcast.stubs.HelloWorld;
import net.maritimecloud.internal.net.messages.BroadcastPublish;
import net.maritimecloud.internal.net.messages.BroadcastRelay;
import net.maritimecloud.internal.net.messages.spi.TransportMessage;
import net.maritimecloud.net.MaritimeCloudClient;
import net.maritimecloud.net.broadcast.BroadcastListener;
import net.maritimecloud.net.broadcast.BroadcastMessageHeader;
import net.maritimecloud.util.geometry.PositionTime;

import org.junit.Ignore;
import org.junit.Test;


/**
 *
 * @author Kasper Nielsen
 */
public class ReconnectTest extends AbstractClientConnectionTest {

    @Test
    public void messageId() throws Exception {
        MaritimeCloudClient c = createAndConnect();

        c.broadcast(new HelloWorld("hello"));
        BroadcastPublish m = t.take(BroadcastPublish.class);
        assertEquals(1L, m.getMessageId().longValue());
        assertEquals(0L, m.getLatestReceivedId().longValue());

        c.broadcast(new HelloWorld("hello"));
        m = t.take(BroadcastPublish.class);
        assertEquals(2L, m.getMessageId().longValue());
        assertEquals(0L, m.getLatestReceivedId().longValue());
    }

    @Test
    public void messageIdMany() throws Exception {
        MaritimeCloudClient c = createAndConnect();
        for (int i = 0; i < 200; i++) {
            c.broadcast(new HelloWorld("hello"));
            BroadcastPublish m = t.take(BroadcastPublish.class);
            assertEquals(i + 1, m.getMessageId().longValue());
            assertEquals(0L, m.getLatestReceivedId().longValue());
        }
    }

    @Test
    public void messageAck() throws Exception {
        MaritimeCloudClient c = createAndConnect();
        final CountDownLatch cdl1 = new CountDownLatch(1);
        final CountDownLatch cdl3 = new CountDownLatch(3);
        c.broadcastListen(HelloWorld.class, new BroadcastListener<HelloWorld>() {
            public void onMessage(HelloWorld broadcast, BroadcastMessageHeader header) {
                cdl1.countDown();
                cdl3.countDown();
            }
        });

        // BroadcastDeliver bm = new BroadcastDeliver(ID3, PositionTime.create(2, 1, 4),
        // HelloWorld.class.getCanonicalName(), persistAndEscape(new HelloWorld("A")));
        BroadcastRelay bm = new BroadcastRelay();
        bm.setLatestReceivedId(0L).setMessageId(0L);
        bm.setChannel(HelloWorld.class.getCanonicalName());
        bm.setMsg(persist(new HelloWorld("A")));
        bm.setPositionTime(PositionTime.create(2, 1, 4));
        bm.setId(ID3.toString());


        bm.setLatestReceivedId(0L);
        bm.setMessageId(1L);
        t.send(bm);
        assertTrue(cdl1.await(1, TimeUnit.SECONDS));

        Thread.sleep(1000);
        c.broadcast(new HelloWorld("hello"));
        BroadcastPublish m = t.take(BroadcastPublish.class);
        assertEquals(1L, m.getMessageId().longValue());
        assertEquals(1L, m.getLatestReceivedId().longValue());

        bm.setLatestReceivedId(1L);
        bm.setMessageId(2L);
        t.send(bm);

        bm.setLatestReceivedId(1L);
        bm.setMessageId(3L);
        t.send(bm);

        assertTrue(cdl3.await(1, TimeUnit.SECONDS));
        c.broadcast(new HelloWorld("hello"));
        m = t.take(BroadcastPublish.class);
        assertEquals(2L, m.getMessageId().longValue());
        assertEquals(3L, m.getLatestReceivedId().longValue());
    }

    /** Tests a mix of messages. */
    @Test
    @Ignore
    public void messageAckMany() throws Exception {
        MaritimeCloudClient c = createAndConnect();
        int count = 200;
        LinkedBlockingDeque<TransportMessage> bq = t.setQueue(new LinkedBlockingDeque<TransportMessage>());
        int lastestOut = 0;
        for (int i = 0; i < count; i++) {
            if (ThreadLocalRandom.current().nextBoolean()) {
                BroadcastRelay bm = new BroadcastRelay();
                bm.setLatestReceivedId(0L).setMessageId(0L);
                bm.setChannel(HelloWorld.class.getCanonicalName());
                bm.setMsg(persist(new HelloWorld("A")));
                bm.setPositionTime(PositionTime.create(2, 1, 4));
                bm.setId(ID3.toString());


                bm.setLatestReceivedId(0L);
                bm.setMessageId((long) ++lastestOut);
                t.send(bm);
            } else {
                c.broadcast(new HelloWorld("hello"));
            }
        }
        for (int i = 0; i < count - lastestOut; i++) {
            assertNotNull(bq.poll(1, TimeUnit.SECONDS));
        }
        bq.clear();
        c.broadcast(new HelloWorld("hello"));
        BroadcastPublish m = t.take(BroadcastPublish.class);
        assertEquals(count - lastestOut + 1, m.getMessageId().longValue());
        assertEquals(lastestOut, m.getLatestReceivedId().longValue());
    }
}
