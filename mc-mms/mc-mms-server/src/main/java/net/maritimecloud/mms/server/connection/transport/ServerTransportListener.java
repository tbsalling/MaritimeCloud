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
package net.maritimecloud.mms.server.connection.transport;

import net.maritimecloud.internal.mms.messages.spi.MmsMessage;
import net.maritimecloud.net.mms.MmsConnectionClosingCode;

/**
 * A listener for transport events.
 *
 * @author Kasper Nielsen
 */
public interface ServerTransportListener {

    /** Invoked whenever the transport has been successfully opened. */
    default void onOpen(ServerTransport t) {}

    /**
     * Invoked whenever a MMS message has been received.
     *
     * @param message
     *            the message that as received
     */
    default void onMessageReceived(ServerTransport t, MmsMessage message) {}

    /**
     * Invoked whenever a MMS message has been sent.
     *
     * @param message
     *            the message that was sent
     */
    default void onMessageSent(ServerTransport t, MmsMessage message) {}

    /**
     * Invoked whenever the transport has been closed. Either remote or locally.
     *
     * @param closingCode
     *            the closing code
     */
    default void onClose(ServerTransport t, MmsConnectionClosingCode closingCode) {}
}
