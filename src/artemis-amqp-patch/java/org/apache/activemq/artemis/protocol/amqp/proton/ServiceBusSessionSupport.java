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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.activemq.artemis.api.core.ActiveMQExceptionType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.MessageReference;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.protocol.amqp.exceptions.ActiveMQAMQPException;
import org.apache.activemq.artemis.utils.collections.LinkedListIterator;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.messaging.Source;
import org.apache.qpid.proton.amqp.transport.AmqpError;
import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.apache.qpid.proton.engine.Sender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Azure Service Bus session-filter adaptation for Artemis sender links. */
public final class ServiceBusSessionSupport {

   private static final Logger LOGGER = LoggerFactory.getLogger(ServiceBusSessionSupport.class);
   private static final Symbol SESSION_FILTER = Symbol.valueOf("com.microsoft:session-filter");
   private static final Symbol LOCKED_UNTIL_UTC = Symbol.valueOf("com.microsoft:locked-until-utc");
   private static final Symbol TIMEOUT_ERROR = Symbol.valueOf("com.microsoft:timeout");
   private static final Symbol SESSION_CANNOT_BE_LOCKED_ERROR =
      Symbol.valueOf("com.microsoft:session-cannot-be-locked");
   private static final Symbol SESSION_LOCK_LOST_ERROR =
      Symbol.valueOf("com.microsoft:session-lock-lost");

   private static final long DOTNET_TICKS_AT_UNIX_EPOCH = 621_355_968_000_000_000L;
   private static final String SESSION_METADATA_PREFIX = "floci-az:servicebus-session:";
   private static final Map<SessionKey, SessionOwner> SESSION_OWNERS = new ConcurrentHashMap<>();
   private static final Map<Sender, SessionKey> SENDER_SESSIONS = new ConcurrentHashMap<>();

   private ServiceBusSessionSupport() {
   }

   public static String configure(ActiveMQServer server,
                                  SimpleString queueName,
                                  Source source,
                                  Map<Symbol, Object> supportedFilters,
                                  Sender protonSender,
                                  ProtonServerSenderContext senderContext,
                                  String existingSelector) throws Exception {
      Map<Symbol, Object> requestedFilters = source.getFilter();
      release(protonSender);
      boolean sessionReceiver = requestedFilters != null && requestedFilters.containsKey(SESSION_FILTER);
      SimpleString normalizedQueueName = normalizeQueueName(queueName);
      Queue queue = server.locateQueue(normalizedQueueName);
      SessionMetadata metadata = sessionMetadata(queue);

      if (metadata == null && sessionReceiver) {
         throw new ActiveMQAMQPException(AmqpError.NOT_ALLOWED,
                                         "The entity is not configured to require sessions",
                                         ActiveMQExceptionType.ILLEGAL_STATE);
      }
      if (metadata != null && !sessionReceiver) {
         throw new ActiveMQAMQPException(AmqpError.NOT_ALLOWED,
                                         "A session receiver is required for this entity",
                                         ActiveMQExceptionType.ILLEGAL_STATE);
      }
      if (!sessionReceiver) {
         return existingSelector;
      }

      Object requestedSession = requestedFilters.get(SESSION_FILTER);
      SessionReservation reservation = requestedSession == null
         ? reserveNextSession(server, queue, normalizedQueueName, protonSender, senderContext, metadata.lockMillis())
         : reserveSession(server, queue, normalizedQueueName, requestedSession.toString(), protonSender,
                          senderContext, metadata.lockMillis());
      if (reservation == null) {
         throw new ActiveMQAMQPException(TIMEOUT_ERROR,
                                         "No unlocked sessions are available",
                                         ActiveMQExceptionType.TIMEOUT_EXCEPTION);
      }

      supportedFilters.put(SESSION_FILTER, reservation.sessionId());
      setLockedUntil(protonSender, reservation.owner().lockedUntilMillis());

      String sessionSelector = "JMSXGroupID = '" + reservation.sessionId().replace("'", "''") + "'";
      return existingSelector == null || existingSelector.isBlank()
         ? sessionSelector
         : "(" + existingSelector + ") AND " + sessionSelector;
   }

   public static void release(Sender protonSender) {
      SessionKey session = SENDER_SESSIONS.remove(protonSender);
      if (session != null) {
         SessionOwner owner = SESSION_OWNERS.get(session);
         if (owner != null && owner.sender() == protonSender) {
            SESSION_OWNERS.remove(session, owner);
         }
      }
   }

   public static String normalizeEntityPath(String entityPath) {
      if (entityPath == null) {
         return null;
      }

      String normalized = entityPath;
      String lowerCasePath = normalized.toLowerCase(Locale.ROOT);
      String subscriptionSegment = "/subscriptions/";
      int subscriptionIndex = lowerCasePath.indexOf(subscriptionSegment);
      if (subscriptionIndex >= 0) {
         normalized = normalized.substring(0, subscriptionIndex)
            + "/Subscriptions/"
            + normalized.substring(subscriptionIndex + subscriptionSegment.length());
      }

      String deadLetterQueueSuffix = "/$DeadLetterQueue";
      int suffixIndex = normalized.length() - deadLetterQueueSuffix.length();
      if (suffixIndex >= 0 && normalized.regionMatches(true,
                                                       suffixIndex,
                                                       deadLetterQueueSuffix,
                                                       0,
                                                       deadLetterQueueSuffix.length())) {
         normalized = normalized.substring(0, suffixIndex) + deadLetterQueueSuffix;
      }
      return normalized;
   }

   private static SessionReservation reserveNextSession(ActiveMQServer server,
                                                        Queue queue,
                                                        SimpleString queueName,
                                                        Sender protonSender,
                                                        ProtonServerSenderContext senderContext,
                                                        long lockMillis) {
      if (queue == null) {
         return null;
      }

      synchronized (queue) {
         Map<SimpleString, ?> lockedGroups = queue.getGroups();
         try (LinkedListIterator<MessageReference> iterator = queue.browserIterator()) {
            while (iterator.hasNext()) {
               SimpleString groupId = iterator.next().getMessage().getGroupID();
               if (groupId == null || lockedGroups.containsKey(groupId)) {
                  continue;
               }

               SessionOwner owner = reserve(server, queue, queueName, groupId.toString(),
                                            protonSender, senderContext, lockMillis);
               if (owner != null) {
                  return new SessionReservation(groupId.toString(), owner);
               }
            }
         }
      }

      return null;
   }

   private static SessionReservation reserveSession(ActiveMQServer server,
                                                    Queue queue,
                                                    SimpleString queueName,
                                                    String sessionId,
                                                    Sender protonSender,
                                                    ProtonServerSenderContext senderContext,
                                                    long lockMillis) throws ActiveMQAMQPException {
      SimpleString groupId = SimpleString.of(sessionId);
      SessionOwner owner = queue == null || queue.getGroups().containsKey(groupId)
         ? null
         : reserve(server, queue, queueName, sessionId, protonSender, senderContext, lockMillis);
      if (owner == null) {
         throw new ActiveMQAMQPException(SESSION_CANNOT_BE_LOCKED_ERROR,
                                         "The requested session is already locked",
                                         ActiveMQExceptionType.ILLEGAL_STATE);
      }
      return new SessionReservation(sessionId, owner);
   }

   private static SessionOwner reserve(ActiveMQServer server,
                                       Queue queue,
                                       SimpleString queueName,
                                       String sessionId,
                                       Sender protonSender,
                                       ProtonServerSenderContext senderContext,
                                       long lockMillis) {
      SessionKey session = new SessionKey(queueName, sessionId);
      SessionOwner candidate = new SessionOwner(
         protonSender, senderContext, queue, System.currentTimeMillis() + lockMillis);
      SessionOwner owner = SESSION_OWNERS.putIfAbsent(session, candidate);
      if (owner == null || owner.sender() == protonSender) {
         if (owner != null) {
            candidate = owner;
         } else {
            scheduleExpiration(server, session, candidate, lockMillis);
         }
         SENDER_SESSIONS.put(protonSender, session);
         return candidate;
      }
      return null;
   }

   private static void scheduleExpiration(ActiveMQServer server,
                                          SessionKey session,
                                          SessionOwner owner,
                                          long lockMillis) {
      server.getScheduledPool().schedule(() -> expire(session, owner), lockMillis, TimeUnit.MILLISECONDS);
   }

   private static void expire(SessionKey session, SessionOwner owner) {
      if (!SESSION_OWNERS.remove(session, owner)) {
         return;
      }

      SENDER_SESSIONS.remove(owner.sender(), session);
      owner.queue().resetGroup(SimpleString.of(session.sessionId()));
      try {
         owner.senderContext().close(new ErrorCondition(
            SESSION_LOCK_LOST_ERROR, "The session lock has expired"));
      } catch (ActiveMQAMQPException e) {
         LOGGER.warn("Failed to close an expired Service Bus session receiver", e);
      }
   }

   private static SimpleString normalizeQueueName(SimpleString queueName) {
      String name = queueName.toString();
      return name.startsWith("/") ? SimpleString.of(name.substring(1)) : queueName;
   }

   private static SessionMetadata sessionMetadata(Queue queue) {
      if (queue == null || queue.getUser() == null) {
         return null;
      }

      String value = queue.getUser().toString();
      if (!value.startsWith(SESSION_METADATA_PREFIX)) {
         return null;
      }

      try {
         long lockSeconds = Long.parseLong(value.substring(SESSION_METADATA_PREFIX.length()));
         if (lockSeconds <= 0) {
            throw new NumberFormatException("Lock duration must be positive");
         }
         return new SessionMetadata(Math.multiplyExact(lockSeconds, 1_000L));
      } catch (ArithmeticException | NumberFormatException e) {
         LOGGER.warn("Ignoring invalid Service Bus session metadata: " + value, e);
         return null;
      }
   }

   private static void setLockedUntil(Sender protonSender, long lockedUntilMillis) {
      Map<Symbol, Object> properties = protonSender.getProperties() == null
         ? new HashMap<>()
         : new HashMap<>(protonSender.getProperties());
      long lockedUntilTicks = DOTNET_TICKS_AT_UNIX_EPOCH
         + lockedUntilMillis * 10_000L;
      properties.put(LOCKED_UNTIL_UTC, lockedUntilTicks);
      protonSender.setProperties(properties);
   }

   private record SessionKey(SimpleString queueName, String sessionId) {
   }

   private record SessionMetadata(long lockMillis) {
   }

   private record SessionOwner(Sender sender,
                               ProtonServerSenderContext senderContext,
                               Queue queue,
                               long lockedUntilMillis) {
   }

   private record SessionReservation(String sessionId, SessionOwner owner) {
   }
}
