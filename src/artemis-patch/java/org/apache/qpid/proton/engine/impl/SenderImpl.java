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

import org.apache.qpid.proton.codec.ReadableBuffer;
import org.apache.qpid.proton.engine.EndpointState;
import org.apache.qpid.proton.engine.Sender;

/**
 * Proton-J 0.34.1 sender with Azure Service Bus compatibility.
 *
 * Azure's SDKs complete a drain only when the sender's flow frame arrives. The stock class
 * writes that frame only when it takes credit back, so a drain that finds the credit already
 * used (by a delivery that crossed the request) is never answered and the receiver waits out
 * its operation timeout. See {@link #drained()}.
 */
public class SenderImpl  extends LinkImpl implements Sender
{
    private int _offered;
    private TransportSender _transportLink;

    SenderImpl(SessionImpl session, String name)
    {
        super(session, name);
    }

    @Override
    public void offer(final int credits)
    {
        _offered = credits;
    }

    @Override
    public int send(final byte[] bytes, int offset, int length)
    {
        if (getLocalState() == EndpointState.CLOSED)
        {
            throw new IllegalStateException("send not allowed after the sender is closed.");
        }
        DeliveryImpl current = current();
        if (current == null || current.getLink() != this)
        {
            throw new IllegalArgumentException();//TODO.
        }
        int sent = current.send(bytes, offset, length);
        if (sent > 0) {
            getSession().incrementOutgoingBytes(sent);
        }
        return sent;
    }

    @Override
    public int send(final ReadableBuffer buffer)
    {
        if (getLocalState() == EndpointState.CLOSED)
        {
            throw new IllegalStateException("send not allowed after the sender is closed.");
        }
        DeliveryImpl current = current();
        if (current == null || current.getLink() != this)
        {
            throw new IllegalArgumentException();
        }
        int sent = current.send(buffer);
        if (sent > 0) {
            getSession().incrementOutgoingBytes(sent);
        }
        return sent;
    }

    @Override
    public int sendNoCopy(final ReadableBuffer buffer)
    {
        if (getLocalState() == EndpointState.CLOSED)
        {
            throw new IllegalStateException("send not allowed after the sender is closed.");
        }
        DeliveryImpl current = current();
        if (current == null || current.getLink() != this)
        {
            throw new IllegalArgumentException();
        }
        int sent = current.sendNoCopy(buffer);
        if (sent > 0) {
            getSession().incrementOutgoingBytes(sent);
        }
        return sent;
    }

    @Override
    public void abort()
    {
        //TODO.
    }

    @Override
    void doFree()
    {
        getSession().freeSender(this);
        super.doFree();
    }

    @Override
    public boolean advance()
    {
        DeliveryImpl delivery = current();
        if (delivery != null) {
            delivery.setComplete();
        }

        boolean advance = super.advance();
        if(advance && _offered > 0)
        {
            _offered--;
        }
        if(advance)
        {
            decrementCredit();
            delivery.addToTransportWorkList();
            getSession().incrementOutgoingDeliveries(1);
        }

        return advance;
    }

    @Override
    public int drained()
    {
        int drained = super.drained();
        if (drained == 0 && getDrain())
        {
            // No credit is left to take back: a delivery used it. The transport writes the
            // drain's flow frame only for a nonzero drained count, and it does so after the
            // pending transfers, so the frame reports the link credit those leave (zero).
            setDrained(1);
            modified();
        }
        return drained;
    }

    boolean hasOfferedCredits()
    {
        return _offered > 0;
    }

    @Override
    TransportSender getTransportLink()
    {
        return _transportLink;
    }

    void setTransportLink(TransportSender transportLink)
    {
        _transportLink = transportLink;
    }


    @Override
    public void setCredit(int credit)
    {
        super.setCredit(credit);
       /* while(getQueued()>0 && getCredit()>0)
        {
            advance();
        }*/
    }

    @Override
    public int getRemoteCredit()
    {
        // Credit is decremented as soon as advance is called on a send,
        // so we need only consider the credit count, not the queued count.
        return getCredit();
    }
}
