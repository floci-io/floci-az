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
import org.apache.qpid.proton.amqp.transport.Flow;
import org.apache.qpid.proton.engine.Event;

/**
 * Proton-J 0.34.1 transport link with Azure Service Bus compatibility.
 *
 * Azure's pre-settled CBS sender omits the AMQP initial-delivery-count field.
 * The stock class leaves the value null and crashes after the first transfer.
 * AMQP delivery numbering starts at zero, so defaulting the missing field to
 * zero is equivalent to the value used by Proton's normal sender path.
 */
class TransportLink<T extends LinkImpl>
{
    private UnsignedInteger _localHandle;
    private String _name;
    private UnsignedInteger _remoteHandle;
    private UnsignedInteger _deliveryCount = UnsignedInteger.ZERO;
    private UnsignedInteger _linkCredit = UnsignedInteger.ZERO;
    private T _link;
    private UnsignedInteger _remoteDeliveryCount;
    private UnsignedInteger _remoteLinkCredit;
    private boolean _detachReceived;
    private boolean _attachSent;
    private boolean _detachSent;

    protected TransportLink(T link)
    {
        _link = link;
        _name = link.getName();
    }

    static <L extends LinkImpl> TransportLink<L> createTransportLink(L link)
    {
        if (link instanceof ReceiverImpl)
        {
            ReceiverImpl r = (ReceiverImpl) link;
            TransportReceiver tr = new TransportReceiver(r);
            r.setTransportLink(tr);

            return (TransportLink<L>) tr;
        }
        else
        {
            SenderImpl s = (SenderImpl) link;
            TransportSender ts = new TransportSender(s);
            s.setTransportLink(ts);

            return (TransportLink<L>) ts;
        }
    }

    void unbind()
    {
        clearLocalHandle();
        clearRemoteHandle();
    }

    public UnsignedInteger getLocalHandle()
    {
        return _localHandle;
    }

    public void setLocalHandle(UnsignedInteger localHandle)
    {
        if (_localHandle == null) {
            _link.incref();
        }
        _localHandle = localHandle;
    }

    public boolean isLocalHandleSet()
    {
        return _localHandle != null;
    }

    public String getName()
    {
        return _name;
    }

    public void setName(String name)
    {
        _name = name;
    }

    public void clearLocalHandle()
    {
        if (_localHandle != null) {
            _link.decref();
        }
        _localHandle = null;
    }

    public UnsignedInteger getRemoteHandle()
    {
        return _remoteHandle;
    }

    public void setRemoteHandle(UnsignedInteger remoteHandle)
    {
        if (_remoteHandle == null) {
            _link.incref();
        }
        _remoteHandle = remoteHandle;
    }

    public void clearRemoteHandle()
    {
        if (_remoteHandle != null) {
            _link.decref();
        }
        _remoteHandle = null;
    }

    public UnsignedInteger getDeliveryCount()
    {
        return _deliveryCount;
    }

    public UnsignedInteger getLinkCredit()
    {
        return _linkCredit;
    }

    public void addCredit(int credits)
    {
        _linkCredit = UnsignedInteger.valueOf(_linkCredit.intValue() + credits);
    }

    public boolean hasCredit()
    {
        return getLinkCredit().compareTo(UnsignedInteger.ZERO) > 0;
    }

    public T getLink()
    {
        return _link;
    }

    void handleFlow(Flow flow)
    {
        _remoteDeliveryCount = flow.getDeliveryCount();
        _remoteLinkCredit = flow.getLinkCredit();

        _link.getConnectionImpl().put(Event.Type.LINK_FLOW, _link);
    }

    void setLinkCredit(UnsignedInteger linkCredit)
    {
        _linkCredit = linkCredit;
    }

    public void setDeliveryCount(UnsignedInteger deliveryCount)
    {
        _deliveryCount = deliveryCount == null ? UnsignedInteger.ZERO : deliveryCount;
    }

    public void settled(TransportDelivery transportDelivery)
    {
        getLink().getSession().getTransportSession().settled(transportDelivery);
    }

    UnsignedInteger getRemoteDeliveryCount()
    {
        return _remoteDeliveryCount;
    }

    UnsignedInteger getRemoteLinkCredit()
    {
        return _remoteLinkCredit;
    }

    public void setRemoteLinkCredit(UnsignedInteger remoteLinkCredit)
    {
        _remoteLinkCredit = remoteLinkCredit;
    }

    void decrementLinkCredit()
    {
        _linkCredit = _linkCredit.subtract(UnsignedInteger.ONE);
    }

    void incrementDeliveryCount()
    {
        _deliveryCount = _deliveryCount.add(UnsignedInteger.ONE);
    }

    public void receivedDetach()
    {
        _detachReceived = true;
    }

    public boolean detachReceived()
    {
        return _detachReceived;
    }

    public boolean attachSent()
    {
        return _attachSent;
    }

    public void sentAttach()
    {
        _attachSent = true;
    }

    public void setRemoteDeliveryCount(UnsignedInteger remoteDeliveryCount)
    {
        _remoteDeliveryCount = remoteDeliveryCount;
    }

    public boolean detachSent()
    {
        return _detachSent;
    }

    public void sentDetach()
    {
        _detachSent = true;
    }
}
