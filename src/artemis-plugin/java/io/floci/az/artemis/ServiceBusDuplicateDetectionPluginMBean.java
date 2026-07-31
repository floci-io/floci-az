package io.floci.az.artemis;

public interface ServiceBusDuplicateDetectionPluginMBean {

    void configure(String address, long historyWindowMillis);

    void remove(String address);
}
