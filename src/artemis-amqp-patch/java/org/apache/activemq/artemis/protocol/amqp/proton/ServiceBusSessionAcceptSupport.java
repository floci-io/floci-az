/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.activemq.artemis.protocol.amqp.proton;

import org.apache.activemq.artemis.api.core.ActiveMQExceptionType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.protocol.amqp.exceptions.ActiveMQAMQPException;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.transport.AmqpError;
import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.apache.qpid.proton.engine.Connection;
import org.apache.qpid.proton.engine.EndpointState;
import org.apache.qpid.proton.engine.Sender;
import org.apache.qpid.proton.engine.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Accept-next-session long poll. Service Bus holds a session receiver's attach whose session filter
 * names no session until a session can be locked, or until the wait the client asked for runs out,
 * and only then answers (or detaches with {@code com.microsoft:timeout}). Azure SDKs send that wait
 * as the {@code com.microsoft:timeout} link property, a little under their own operation timeout.
 *
 * <p>While a waiter is pending its attach is left unanswered: {@link ServiceBusSessionSupport#configure}
 * throws {@link AcceptPending}, which {@code AMQPSessionContext.addSender} swallows without replying.
 * A poll on the broker's scheduled pool hops onto the connection's event loop and, once a session is
 * free or the wait is over, replays the attach through {@link AMQPConnectionContext#onRemoteOpen}, so the
 * reservation and the reply take Artemis's own path. All waiter state for a link is read and written on
 * that link's event loop; the scheduled pool only queues work there.
 */
public final class ServiceBusSessionAcceptSupport {

   private static final Logger LOGGER = LoggerFactory.getLogger(ServiceBusSessionAcceptSupport.class);
   private static final Symbol LINK_TIMEOUT = Symbol.valueOf("com.microsoft:timeout");

   /**
    * The wait when the attach carries no {@code com.microsoft:timeout}. The Java SDK documents the
    * broker's default as 60 s, and the Python SDK, which omits the property unless max_wait_time is
    * set, waits 65 s for the attach reply; 60 s answers before that client gives up.
    */
   static final long DEFAULT_WAIT_MILLIS = 60_000L;

   /** How often a waiter looks for a session it could lock. */
   static final long POLL_MILLIS = 50L;

   private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS);

   /** How long a queue's scan record outlives its last scan before it is dropped. */
   static final long IDLE_SCAN_NANOS = 20 * POLL_NANOS;

   private static final Map<Sender, PendingAccept> PENDING = new ConcurrentHashMap<>();

   /**
    * The latest backlog scan of each queue with waiters. The scan walks the backlog under the queue's
    * lock, so the waiters on a queue share one scan per poll interval instead of each running its own:
    * a waiter that finds a scan in progress, or one that found no free session within the interval,
    * waits for its next poll. A session freed meanwhile is seen at most one interval later. A record
    * not scanned for {@link #IDLE_SCAN_NANOS} is dropped, so deleted queues leave nothing behind.
    */
   private static final Map<SimpleString, SessionScan> SCANS = new ConcurrentHashMap<>();

   private ServiceBusSessionAcceptSupport() {
   }

   /** Signals that the attach stays unanswered while it waits for a session. */
   static final class AcceptPending extends ActiveMQAMQPException {
      private static final long serialVersionUID = 1L;

      AcceptPending() {
         super(AmqpError.RESOURCE_LIMIT_EXCEEDED, "Waiting for an unlocked session", ActiveMQExceptionType.TIMEOUT_EXCEPTION);
      }

      @Override
      public synchronized Throwable fillInStackTrace() {
         return this;
      }
   }

   /** True when {@code AMQPSessionContext.addSender} must leave the attach unanswered. */
   public static boolean isPending(ActiveMQAMQPException e) {
      return e instanceof AcceptPending;
   }

   /**
    * Called on the event loop when no session could be locked for an accept-next-session attach.
    * Returns true when the attach should keep waiting (a poll is scheduled), false when the wait is
    * over and the attach must fail with {@code com.microsoft:timeout} now.
    */
   static boolean await(ActiveMQServer server, Queue queue, SimpleString queueName, Sender sender,
                        AMQPConnectionContext connection) {
      long now = System.nanoTime();
      PendingAccept pending = PENDING.get(sender);
      if (pending == null) {
         long waitMillis = requestedWaitMillis(sender);
         if (waitMillis <= 0) {
            return false;
         }
         pending = new PendingAccept(server, queue, queueName, sender, connection,
                                     now + TimeUnit.MILLISECONDS.toNanos(waitMillis), new AtomicReference<>());
         PENDING.put(sender, pending);
         LOGGER.debug("Holding accept-next-session link {} on {} for up to {} ms",
                      sender.getName(), queueName, waitMillis);
      } else if (now - pending.deadlineNanos() >= 0) {
         PENDING.remove(sender, pending);
         return false;
      }
      schedulePoll(pending);
      return true;
   }

   /** The attach reserved a session; it no longer waits. */
   static void accepted(Sender sender) {
      PendingAccept pending = PENDING.remove(sender);
      if (pending != null) {
         pending.cancelTimer();
      }
   }

   static long requestedWaitMillis(Sender sender) {
      Map<Symbol, Object> properties = sender.getRemoteProperties();
      Object requested = properties == null ? null : properties.get(LINK_TIMEOUT);
      return requested instanceof Number number ? number.longValue() : DEFAULT_WAIT_MILLIS;
   }

   private static void schedulePoll(PendingAccept pending) {
      long remainingMillis = TimeUnit.NANOSECONDS.toMillis(pending.deadlineNanos() - System.nanoTime());
      long delay = Math.max(0L, Math.min(POLL_MILLIS, remainingMillis));
      pending.timer().set(pending.server().getScheduledPool().schedule(
         () -> pending.connection().runLater(() -> poll(pending)), delay, TimeUnit.MILLISECONDS));
   }

   private static void poll(PendingAccept pending) {
      Sender sender = pending.sender();
      if (PENDING.get(sender) != pending) {
         return;
      }
      if (!attaching(sender)) {
         PENDING.remove(sender, pending);
         LOGGER.debug("Accept-next-session link {} closed while waiting for a session", sender.getName());
         return;
      }
      boolean due = System.nanoTime() - pending.deadlineNanos() >= 0;
      if (!due && !mayLockASession(pending)) {
         schedulePoll(pending);
         return;
      }

      // Replay the attach. configure() either reserves a session (and the link opens), defers again
      // after losing a race for the session (and schedules a new poll), or times it out.
      ScheduledFuture<?> before = pending.timer().get();
      try {
         pending.connection().onRemoteOpen(sender);
      } catch (Exception e) {
         PENDING.remove(sender, pending);
         LOGGER.warn("Failed to complete a pending accept-next-session attach for link {}", sender.getName(), e);
         sender.setSource(null);
         sender.setCondition(new ErrorCondition(AmqpError.INTERNAL_ERROR, e.getMessage()));
         sender.close();
         pending.connection().flush();
         return;
      }
      if (pending.timer().get() == before) {
         // Answered or abandoned without deferring again: nothing will poll this waiter any more.
         PENDING.remove(sender, pending);
      }
   }

   private static boolean mayLockASession(PendingAccept pending) {
      SimpleString queueName = pending.queueName();
      SessionScan scan = SCANS.computeIfAbsent(queueName, name -> new SessionScan());
      if (!scan.lock.tryLock()) {
         // Another waiter is scanning this queue right now; its result covers this poll too.
         return false;
      }
      long now = System.nanoTime();
      boolean free;
      try {
         if (scan.foundNoFreeSessionWithin(now)) {
            return false;
         }
         scan.lastScanNanos = now;
         free = ServiceBusSessionSupport.hasUnlockedSession(pending.queue(), queueName);
         scan.noFreeSession = !free;
      } finally {
         scan.lock.unlock();
      }
      evictIdleScans(now);
      return free;
   }

   /** Drops the scan records of queues nobody has scanned for {@link #IDLE_SCAN_NANOS}. */
   static void evictIdleScans(long nowNanos) {
      SCANS.values().removeIf(scan -> nowNanos - scan.lastScanNanos >= IDLE_SCAN_NANOS);
   }

   private static boolean attaching(Sender sender) {
      Session session = sender.getSession();
      Connection connection = session == null ? null : session.getConnection();
      return connection != null
         && sender.getLocalState() == EndpointState.UNINITIALIZED
         && sender.getRemoteState() == EndpointState.ACTIVE
         && session.getLocalState() == EndpointState.ACTIVE
         && session.getRemoteState() == EndpointState.ACTIVE
         && connection.getLocalState() == EndpointState.ACTIVE
         && connection.getRemoteState() == EndpointState.ACTIVE;
   }

   /** One queue's latest backlog scan. Written under {@code lock}; read without it only to evict. */
   private static final class SessionScan {
      private final ReentrantLock lock = new ReentrantLock();
      private volatile long lastScanNanos = System.nanoTime();
      private volatile boolean noFreeSession;

      private boolean foundNoFreeSessionWithin(long nowNanos) {
         return noFreeSession && nowNanos - lastScanNanos < POLL_NANOS;
      }
   }

   private record PendingAccept(ActiveMQServer server,
                                Queue queue,
                                SimpleString queueName,
                                Sender sender,
                                AMQPConnectionContext connection,
                                long deadlineNanos,
                                AtomicReference<ScheduledFuture<?>> timer) {
      void cancelTimer() {
         ScheduledFuture<?> scheduled = timer.getAndSet(null);
         if (scheduled != null) {
            scheduled.cancel(false);
         }
      }
   }
}
