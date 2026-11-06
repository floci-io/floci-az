package io.floci.az.services.servicebus;

/** Fired when a namespace's Artemis broker is ready; every broker starts with no entities. */
record ServiceBusBrokerStarted(String namespace) {
}
