/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.proton.engine.impl;

import org.apache.qpid.proton.amqp.UnsignedInteger;
import org.apache.qpid.proton.amqp.UnsignedLong;
import org.apache.qpid.proton.codec.ReadableBuffer;
import org.apache.qpid.proton.codec.WritableBuffer;
import org.apache.qpid.proton.engine.Receiver;

public class ReceiverImpl extends LinkImpl implements Receiver
{
    private static final UnsignedLong DEFAULT_MAX_MESSAGE_SIZE = UnsignedLong.valueOf(1_048_576L);

    private boolean _drainFlagMode = true;

    @Override
    public boolean advance()
    {
        DeliveryImpl current = current();
        if(current != null)
        {
            current.setDone();
        }
        final boolean advance = super.advance();
        if(advance)
        {
            decrementQueued();
            decrementCredit();
            getSession().incrementIncomingBytes(-current.pending());
            getSession().incrementIncomingDeliveries(-1);
            if (getSession().getTransportSession().getIncomingWindowSize().equals(UnsignedInteger.ZERO)) {
                modified();
            }
        }
        return advance;
    }

    private TransportReceiver _transportReceiver;
    private int _unsentCredits;

    ReceiverImpl(SessionImpl session, String name)
    {
        super(session, name);
        // Proton's null encodes as AMQP's unlimited default. Azure's .NET SDK
        // reads that uint64 value as signed -1 and rejects every message.
        setMaxMessageSize(DEFAULT_MAX_MESSAGE_SIZE);
    }

    @Override
    public void flow(final int credits)
    {
        addCredit(credits);
        _unsentCredits += credits;
        modified();
        if (!_drainFlagMode)
        {
            setDrain(false);
            _drainFlagMode = false;
        }
    }

    int clearUnsentCredits()
    {
        int credits = _unsentCredits;
        _unsentCredits = 0;
        return credits;
    }

    @Override
    public int recv(final byte[] bytes, int offset, int size)
    {
        if (_current == null) {
            throw new IllegalStateException("no current delivery");
        }

        int consumed = _current.recv(bytes, offset, size);
        if (consumed > 0) {
            getSession().incrementIncomingBytes(-consumed);
            if (getSession().getTransportSession().getIncomingWindowSize().equals(UnsignedInteger.ZERO)) {
                modified();
            }
        }
        return consumed;
    }

    @Override
    public int recv(final WritableBuffer buffer)
    {
        if (_current == null) {
            throw new IllegalStateException("no current delivery");
        }

        int consumed = _current.recv(buffer);
        if (consumed > 0) {
            getSession().incrementIncomingBytes(-consumed);
            if (getSession().getTransportSession().getIncomingWindowSize().equals(UnsignedInteger.ZERO)) {
                modified();
            }
        }
        return consumed;
    }

    @Override
    public ReadableBuffer recv()
    {
        if (_current == null) {
            throw new IllegalStateException("no current delivery");
        }

        ReadableBuffer consumed = _current.recv();
        if (consumed.remaining() > 0) {
            getSession().incrementIncomingBytes(-consumed.remaining());
            if (getSession().getTransportSession().getIncomingWindowSize().equals(UnsignedInteger.ZERO)) {
                modified();
            }
        }
        return consumed;
    }

    @Override
    void doFree()
    {
        getSession().freeReceiver(this);
        super.doFree();
    }

    boolean hasIncoming()
    {
        return false;
    }

    void setTransportLink(TransportReceiver transportReceiver)
    {
        _transportReceiver = transportReceiver;
    }

    @Override
    TransportReceiver getTransportLink()
    {
        return _transportReceiver;
    }

    @Override
    public void drain(int credit)
    {
        setDrain(true);
        flow(credit);
        _drainFlagMode = false;
    }

    @Override
    public boolean draining()
    {
        return getDrain() && (getCredit() > getQueued());
    }

    @Override
    public void setDrain(boolean drain)
    {
        super.setDrain(drain);
        modified();
        _drainFlagMode = true;
    }

    @Override
    public int getRemoteCredit()
    {
        return getCredit() - getQueued();
    }
}
