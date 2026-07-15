package io.floci.az.core.auth;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

class UserDelegationKeyMaterialTest {

    @Test
    void accountKeyIsStableWithinOneEmulatorProcess() {
        UserDelegationKeyMaterial material = new UserDelegationKeyMaterial();

        assertThat(material.signingKeyForAccount("account"),
                equalTo(material.signingKeyForAccount("account")));
    }

    @Test
    void accountKeysAreIsolated() {
        UserDelegationKeyMaterial material = new UserDelegationKeyMaterial();

        assertThat(material.signingKeyForAccount("account-a"),
                not(equalTo(material.signingKeyForAccount("account-b"))));
    }

    @Test
    void accountNameDoesNotDetermineKeyAcrossEmulatorProcesses() {
        UserDelegationKeyMaterial firstProcess = new UserDelegationKeyMaterial();
        UserDelegationKeyMaterial secondProcess = new UserDelegationKeyMaterial();

        assertThat(firstProcess.signingKeyForAccount("account"),
                not(equalTo(secondProcess.signingKeyForAccount("account"))));
    }

    @Test
    void derivedSigningKeyContains256Bits() {
        UserDelegationKeyMaterial material = new UserDelegationKeyMaterial();

        assertThat(Base64.getDecoder().decode(material.signingKeyForAccount("account")).length, equalTo(32));
    }
}
