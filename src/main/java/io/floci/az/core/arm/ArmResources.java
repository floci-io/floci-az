package io.floci.az.core.arm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Conventions for ARM resources held as maps: services smuggle their storage keys as
 * underscore-prefixed entries and strip them before returning the resource on the wire.
 */
public final class ArmResources {

    public static final String SUB_KEY = "_sub";
    public static final String RG_KEY = "_rg";

    private ArmResources() {
    }

    /** Copy of the resource without the internal routing keys. */
    public static Map<String, Object> stripInternal(Map<String, Object> resource) {
        Map<String, Object> copy = new LinkedHashMap<>(resource);
        copy.remove(SUB_KEY);
        copy.remove(RG_KEY);
        return copy;
    }
}
