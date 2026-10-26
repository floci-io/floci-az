package io.floci.az.services.entra;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusMainTest
@TestProfile(DirectorySeedStartupTest.MissingSeed.class)
public class DirectorySeedStartupTest {

    public static class MissingSeed implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.package.main-class", "directory-seed-startup-test",
                    "floci-az.services.entra.seed-file", seedFile().toAbsolutePath().toString());
        }

        protected Path seedFile() {
            return Path.of("src/test/resources/entra/missing-directory-seed.json");
        }
    }

    /** Exit after startup without resolving or calling any directory bean. */
    @QuarkusMain(name = "directory-seed-startup-test")
    public static class ExitAfterStartup implements QuarkusApplication {
        @Override
        public int run(String... args) {
            return 0;
        }
    }

    @Test
    void missingSeedFailsBeforeReadinessWithoutADirectoryRequest(QuarkusMainLauncher launcher) {
        assertSeedRejected(launcher);
    }

    static void assertSeedRejected(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch();
        String output = result.getOutput() + result.getErrorOutput();
        assertEquals(1, result.exitCode(), output);
        assertTrue(output.contains("Cannot read or parse Entra directory seed file"), output);
        assertFalse(output.contains("floci-az is ready"), output);
        assertFalse(output.contains("private-startup-seed-secret"), output);
    }
}
