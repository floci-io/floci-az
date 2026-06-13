package io.floci.az.services.network;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class NetworkStore {

    final Map<String, Map<String, Object>> resources = new ConcurrentHashMap<>();
}
