package io.floci.az.artemis;

import org.apache.activemq.artemis.protocol.amqp.sasl.PlainSASLResult;
import org.apache.activemq.artemis.protocol.amqp.sasl.SASLResult;
import org.apache.activemq.artemis.protocol.amqp.sasl.ServerSASL;

final class MssbcbsServerSasl implements ServerSASL {

    static final String MECHANISM = "MSSBCBS";

    @Override
    public String getName() {
        return MECHANISM;
    }

    @Override
    public byte[] processSASL(byte[] bytes) {
        return null;
    }

    @Override
    public SASLResult result() {
        return new PlainSASLResult(true, null, null);
    }

    @Override
    public void done() {
    }
}
