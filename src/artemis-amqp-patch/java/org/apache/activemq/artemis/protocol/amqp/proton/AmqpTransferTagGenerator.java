/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership. The ASF licenses this
 * file to you under the Apache License, Version 2.0.
 */
package org.apache.activemq.artemis.protocol.amqp.proton;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Generates 16-byte delivery tags compatible with Azure Service Bus lock tokens.
 */
public final class AmqpTransferTagGenerator {

   public static final int DEFAULT_TAG_POOL_SIZE = 1024;
   private static final int AZURE_LOCK_TOKEN_SIZE = 16;

   private final Deque<byte[]> tagPool;
   private long nextTagId = 1;
   private int maxPoolSize = DEFAULT_TAG_POOL_SIZE;

   public AmqpTransferTagGenerator() {
      this(true);
   }

   public AmqpTransferTagGenerator(boolean pool) {
      tagPool = pool ? new ArrayDeque<>() : null;
   }

   public synchronized byte[] getNextTag() {
      byte[] tagBytes = tagPool == null ? null : tagPool.pollFirst();
      if (tagBytes == null) {
         tagBytes = new byte[AZURE_LOCK_TOKEN_SIZE];
         long tag = nextTagId++;
         for (int i = 0; i < Long.BYTES; i++) {
            tagBytes[AZURE_LOCK_TOKEN_SIZE - 1 - i] = (byte) (tag >>> (i * 8));
         }
      }
      return tagBytes;
   }

   public synchronized void returnTag(byte[] data) {
      if (tagPool != null && tagPool.size() < maxPoolSize) {
         tagPool.offerLast(data);
      }
   }

   public int getMaxPoolSize() {
      return maxPoolSize;
   }

   public void setMaxPoolSize(int maxPoolSize) {
      this.maxPoolSize = maxPoolSize;
   }

   public boolean isPooling() {
      return tagPool != null;
   }
}
