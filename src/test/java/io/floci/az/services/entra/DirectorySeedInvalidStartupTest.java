package io.floci.az.services.entra;

import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

@QuarkusMainTest
@TestProfile(DirectorySeedInvalidStartupTest.MalformedSeed.class)
class DirectorySeedInvalidStartupTest {

    public static class MalformedSeed extends DirectorySeedStartupTest.MissingSeed {
        @Override
        protected Path seedFile() {
            return Path.of("src/test/resources/entra/directory-seed-invalid.json");
        }
    }

    @Test
    void malformedSeedFailsBeforeReadinessWithoutADirectoryRequest(QuarkusMainLauncher launcher) {
        DirectorySeedStartupTest.assertSeedRejected(launcher);
    }
}
