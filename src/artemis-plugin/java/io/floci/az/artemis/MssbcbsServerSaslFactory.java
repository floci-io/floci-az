package io.floci.az.artemis;

import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.protocol.amqp.broker.AmqpInterceptor;
import org.apache.activemq.artemis.protocol.amqp.proton.AMQPRoutingHandler;
import org.apache.activemq.artemis.protocol.amqp.sasl.ServerSASL;
import org.apache.activemq.artemis.protocol.amqp.sasl.ServerSASLFactory;
import org.apache.activemq.artemis.spi.core.protocol.ProtocolManager;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.apache.activemq.artemis.spi.core.remoting.Connection;

public final class MssbcbsServerSaslFactory implements ServerSASLFactory {

    @Override
    public String getMechanism() {
        return MssbcbsServerSasl.MECHANISM;
    }

    @Override
    public ServerSASL create(
            ActiveMQServer server,
            ProtocolManager<AmqpInterceptor, AMQPRoutingHandler> manager,
            Connection connection,
            RemotingConnection remotingConnection) {
        return new MssbcbsServerSasl();
    }

    @Override
    public int getPrecedence() {
        return Integer.MIN_VALUE;
    }

    @Override
    public boolean isDefaultPermitted() {
        return true;
    }
}
