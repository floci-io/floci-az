package io.floci.az.config;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.floci.az.config.EmulatorConfig.SqlDataPlaneProvider.MANAGED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmulatorConfigSqlTest {

    @Test
    @SuppressWarnings("deprecation")
    void acceptedEulaPreservesLegacyManagedProvider() {
        EmulatorConfig.SqlServiceConfig sql = mock(EmulatorConfig.SqlServiceConfig.class, CALLS_REAL_METHODS);
        EmulatorConfig.SqlDataPlaneConfig dataPlane = mock(EmulatorConfig.SqlDataPlaneConfig.class);
        when(sql.dataPlane()).thenReturn(dataPlane);
        when(dataPlane.provider()).thenReturn(Optional.empty());
        when(sql.mocked()).thenReturn(Optional.empty());
        when(sql.acceptEula()).thenReturn("Y");

        assertEquals(MANAGED, sql.dataPlaneProvider());
    }
}
