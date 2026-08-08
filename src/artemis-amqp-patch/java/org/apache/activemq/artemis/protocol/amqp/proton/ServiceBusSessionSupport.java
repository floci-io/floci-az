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

import org.apache.activemq.artemis.api.core.ActiveMQExceptionType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.MessageReference;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.protocol.amqp.exceptions.ActiveMQAMQPException;
import org.apache.activemq.artemis.utils.collections.LinkedListIterator;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.messaging.Source;
import org.apache.qpid.proton.engine.Sender;

/** Azure Service Bus session-filter adaptation for Artemis sender links. */
public final class ServiceBusSessionSupport {

   private static final Symbol SESSION_FILTER = Symbol.valueOf("com.microsoft:session-filter");
   private static final Symbol LOCKED_UNTIL_UTC = Symbol.valueOf("com.microsoft:locked-until-utc");
   private static final Symbol TIMEOUT_ERROR = Symbol.valueOf("com.microsoft:timeout");
   private static final Symbol SESSION_CANNOT_BE_LOCKED_ERROR =
      Symbol.valueOf("com.microsoft:session-cannot-be-locked");

   private static final long DOTNET_TICKS_AT_UNIX_EPOCH = 621_355_968_000_000_000L;
   private static final long SESSION_LOCK_MILLIS = 60_000L;
   private static final Map<SessionKey, Sender> SESSION_OWNERS = new ConcurrentHashMap<>();
   private static final Map<Sender, SessionKey> SENDER_SESSIONS = new ConcurrentHashMap<>();

   private ServiceBusSessionSupport() {
   }

   public static String configure(ActiveMQServer server,
                                  SimpleString queueName,
                                  Source source,
                                  Map<Symbol, Object> supportedFilters,
                                  Sender protonSender,
                                  String existingSelector) throws Exception {
      Map<Symbol, Object> requestedFilters = source.getFilter();
      release(protonSender);
      if (requestedFilters == null || !requestedFilters.containsKey(SESSION_FILTER)) {
         return existingSelector;
      }

      SimpleString normalizedQueueName = normalizeQueueName(queueName);
      Queue queue = server.locateQueue(normalizedQueueName);
      Object requestedSession = requestedFilters.get(SESSION_FILTER);
      String sessionId = requestedSession == null
         ? reserveNextSession(queue, normalizedQueueName, protonSender)
         : reserveSession(queue, normalizedQueueName, requestedSession.toString(), protonSender);
      if (sessionId == null) {
         throw new ActiveMQAMQPException(TIMEOUT_ERROR,
                                         "No unlocked sessions are available",
                                         ActiveMQExceptionType.TIMEOUT_EXCEPTION);
      }

      supportedFilters.put(SESSION_FILTER, sessionId);
      setLockedUntil(protonSender);

      String sessionSelector = "JMSXGroupID = '" + sessionId.replace("'", "''") + "'";
      return existingSelector == null || existingSelector.isBlank()
         ? sessionSelector
         : "(" + existingSelector + ") AND " + sessionSelector;
   }

   public static void release(Sender protonSender) {
      SessionKey session = SENDER_SESSIONS.remove(protonSender);
      if (session != null) {
         SESSION_OWNERS.remove(session, protonSender);
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

   private static String reserveNextSession(Queue queue, SimpleString queueName, Sender protonSender) {
      if (queue == null) {
         return null;
      }

      synchronized (queue) {
         Map<SimpleString, ?> lockedGroups = queue.getGroups();
         try (LinkedListIterator<MessageReference> iterator = queue.browserIterator()) {
            while (iterator.hasNext()) {
               SimpleString groupId = iterator.next().getMessage().getGroupID();
               if (groupId != null
                   && !lockedGroups.containsKey(groupId)
                   && reserve(queueName, groupId.toString(), protonSender)) {
                  return groupId.toString();
               }
            }
         }
      }

      return null;
   }

   private static String reserveSession(Queue queue,
                                        SimpleString queueName,
                                        String sessionId,
                                        Sender protonSender) throws ActiveMQAMQPException {
      SimpleString groupId = SimpleString.of(sessionId);
      if ((queue != null && queue.getGroups().containsKey(groupId))
          || !reserve(queueName, sessionId, protonSender)) {
         throw new ActiveMQAMQPException(SESSION_CANNOT_BE_LOCKED_ERROR,
                                         "The requested session is already locked",
                                         ActiveMQExceptionType.ILLEGAL_STATE);
      }
      return sessionId;
   }

   private static boolean reserve(SimpleString queueName, String sessionId, Sender protonSender) {
      SessionKey session = new SessionKey(queueName, sessionId);
      Sender owner = SESSION_OWNERS.putIfAbsent(session, protonSender);
      if (owner == null || owner == protonSender) {
         SENDER_SESSIONS.put(protonSender, session);
         return true;
      }
      return false;
   }

   private static SimpleString normalizeQueueName(SimpleString queueName) {
      String name = queueName.toString();
      return name.startsWith("/") ? SimpleString.of(name.substring(1)) : queueName;
   }

   private static void setLockedUntil(Sender protonSender) {
      Map<Symbol, Object> properties = protonSender.getProperties() == null
         ? new HashMap<>()
         : new HashMap<>(protonSender.getProperties());
      long lockedUntilTicks = DOTNET_TICKS_AT_UNIX_EPOCH
         + (System.currentTimeMillis() + SESSION_LOCK_MILLIS) * 10_000L;
      properties.put(LOCKED_UNTIL_UTC, lockedUntilTicks);
      protonSender.setProperties(properties);
   }

   private record SessionKey(SimpleString queueName, String sessionId) {
   }
}
