/*
 * Licensed to Apereo under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Apereo licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License.  You may obtain a
 * copy of the License at the following location:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jasig.cas.ticket.registry;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import org.jasig.cas.ticket.ServiceTicket;
import org.jasig.cas.ticket.Ticket;
import org.jasig.cas.ticket.TicketGrantingTicket;
import org.jasig.cas.ticket.registry.encrypt.AbstractCrypticTicketRegistry;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * Hazelcast-based implementation of a {@link TicketRegistry}.
 * <p/>
 * <p>This implementation just wraps the Hazelcast's {@link IMap}
 * which is an extension of the standard Java's <code>ConcurrentMap</code>.</p>
 * <p>The heavy lifting of distributed data partitioning, network cluster discovery and
 * join, data replication, etc. is done by Hazelcast's Map implementation.</p>
 *
 * @author Dmitriy Kopylenko
 * @author Jonathan Johnson
 * @since 4.1.0
 */
@Component("hazelcastTicketRegistry")
public class HazelcastTicketRegistry extends AbstractCrypticTicketRegistry implements DisposableBean {

    private final IMap<String, Ticket> registry;

    private final long serviceTicketTimeoutInSeconds;

    private final long ticketGrantingTicketTimoutInSeconds;

    private final HazelcastInstance hz;


    /**
     * @param hz                                  An instance of <code>HazelcastInstance</code>
     * @param mapName                             Name of map to use
     * @param ticketGrantingTicketTimoutInSeconds TTL for TGT entries
     * @param serviceTicketTimeoutInSeconds       TTL for ST entries
     */
    @Autowired
    public HazelcastTicketRegistry(
        @Qualifier("hazelcast")
        final HazelcastInstance hz,
        @Value("${hz.mapname:tickets}")
        final String mapName,
        @Value("${tgt.maxTimeToLiveInSeconds:28800}")
        final long ticketGrantingTicketTimoutInSeconds,
        @Value("${st.timeToKillInSeconds:10}")
        final long serviceTicketTimeoutInSeconds) {

        logInitialization(hz, mapName, ticketGrantingTicketTimoutInSeconds, serviceTicketTimeoutInSeconds);
        this.registry = hz.getMap(mapName);
        this.ticketGrantingTicketTimoutInSeconds = ticketGrantingTicketTimoutInSeconds;
        this.serviceTicketTimeoutInSeconds = serviceTicketTimeoutInSeconds;
        this.hz = hz;
    }


    @Override
    protected void updateTicket(final Ticket ticket) {
        addTicket(ticket);
    }


    @Override
    protected boolean needsCallback() {
        return false;
    }

    /**
     * @param hz                                  An instance of <code>HazelcastInstance</code>
     * @param mapName                             Name of map to use
     * @param ticketGrantingTicketTimoutInSeconds TTL for TGT entries
     * @param serviceTicketTimeoutInSeconds       TTL for ST entries
     */
    private void logInitialization(final HazelcastInstance hz,
                                   final String mapName,
                                   final long ticketGrantingTicketTimoutInSeconds,
                                   final long serviceTicketTimeoutInSeconds) {

        logger.info("Setting up Hazelcast Ticket Registry...");
        logger.debug("Hazelcast instance: {}", hz);
        logger.debug("TGT timeout: [{}s]", ticketGrantingTicketTimoutInSeconds);
        logger.debug("ST timeout: [{}s]", serviceTicketTimeoutInSeconds);
    }

    @Override
    public void addTicket(final Ticket ticket) {
        addTicket(ticket, getTimeout(ticket));
    }

    /**
     * Adds the ticket to the hazelcast instance.
     *
     * @param ticket a ticket
     * @param ttl    time to live in seconds
     */
    private void addTicket(final Ticket ticket, final long ttl) {
        logger.debug("Adding ticket [{}] with ttl [{}s]", ticket.getId(), ttl);
        final Ticket encTicket = encodeTicket(ticket);
        this.registry.set(encTicket.getId(), encTicket, ttl, TimeUnit.SECONDS);
    }


    @Override
    public Ticket getTicket(final String ticketId) {
        final String encTicketId = encodeTicketId(ticketId);
        final Ticket ticket = this.registry.get(encTicketId);
        return decodeTicket(ticket);
    }


    @Override
    public boolean deleteTicket(final String ticketId) {
        final String encTicketId = encodeTicketId(ticketId);
        logger.debug("Removing ticket [{}]", encTicketId);
        return this.registry.remove(encTicketId) != null;
    }

    @Override
    public Collection<Ticket> getTickets() {
        return decodeTickets(this.registry.values());
    }

    /**
     * A method to get the starting TTL for a ticket based upon type.
     *
     * @param t Ticket to get starting TTL for
     * @return Initial TTL for ticket
     */
    private long getTimeout(final Ticket t) {
        if (t instanceof TicketGrantingTicket) {
            return this.ticketGrantingTicketTimoutInSeconds;
        } else if (t instanceof ServiceTicket) {
            return this.serviceTicketTimeoutInSeconds;
        }
        throw new IllegalArgumentException(
            String.format("Invalid ticket type [%s]. Expecting either [TicketGrantingTicket] or [ServiceTicket]",
                t.getClass().getName()));
    }

    /**
     * Make sure we shutdown HazelCast when the context is destroyed.
     */
    public void shutdown() {
        this.hz.shutdown();
    }

    @Override
    public void destroy() throws Exception {
        shutdown();
    }
}
