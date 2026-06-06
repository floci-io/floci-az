package io.floci.az.services.apim;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ApiManagementStore {

    final Map<String, Map<String, Object>> services = new ConcurrentHashMap<>();
    final Map<String, Map<String, Object>> apis = new ConcurrentHashMap<>();
    final Map<String, Map<String, Object>> operations = new ConcurrentHashMap<>();
    final Map<String, Map<String, Object>> policies = new ConcurrentHashMap<>();
    final Map<String, Map<String, Object>> products = new ConcurrentHashMap<>();
    final Map<String, Map<String, Object>> subscriptions = new ConcurrentHashMap<>();
    final Map<String, Map<String, Object>> namedValues = new ConcurrentHashMap<>();
    final Map<String, Map<String, Object>> backends = new ConcurrentHashMap<>();
    final Map<String, String> productApis = new ConcurrentHashMap<>();
}
