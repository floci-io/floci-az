package io.floci.az.services.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlServerManagerTest {

    @Test
    void readinessTimeoutFailsInsteadOfReportingSuccess() {
        assertThrows(IllegalStateException.class,
                () -> SqlServerManager.waitForReady("127.0.0.1", 1, 0));
    }
}
