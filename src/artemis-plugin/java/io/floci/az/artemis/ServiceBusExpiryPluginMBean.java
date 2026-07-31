package io.floci.az.artemis;

public interface ServiceBusExpiryPluginMBean {

    void configure(String queueName, long defaultTtlMillis, String deadLetterAddress);

    void remove(String queueName);
}
