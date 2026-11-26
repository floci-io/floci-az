package io.floci.az.services.iothub;

import java.security.SecureRandom;
import java.util.Base64;

/** The symmetric keys IoT Hub issues, for its shared access policies and for its devices alike. */
final class IotHubKeys {

    /** Azure's keys are 32 random bytes. */
    static final int KEY_BYTES = 32;

    private IotHubKeys() {
    }

    /** A new key as Azure issues one: {@value #KEY_BYTES} random bytes, base64. */
    static String newKey(SecureRandom random) {
        byte[] key = new byte[KEY_BYTES];
        random.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
