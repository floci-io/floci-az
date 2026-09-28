package io.floci.az.services.mysql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MySqlConnectionInfo: connection string builder")
class MySqlConnectionInfoTest {

    @Test
    @DisplayName("an IPv6 host is bracketed in URLs and bare for the CLI")
    void ipv6Host() {
        MySqlConnectionInfo info = MySqlConnectionInfo.of("::1", 3306, "admin", "Pass123!", "orders");
        assertTrue(info.jdbcUrl().startsWith("jdbc:mysql://[::1]:3306/orders"), info.jdbcUrl());
        assertTrue(info.uri().startsWith("mysql://admin:Pass123!@[::1]:3306/orders"), info.uri());
        assertTrue(info.mysql().contains("::1") && !info.mysql().contains("[::1]"), info.mysql());
    }

    @Test
    @DisplayName("a hostname is unchanged")
    void hostname() {
        MySqlConnectionInfo info = MySqlConnectionInfo.of("localhost", 3306, "admin", "Pass123!", "orders");
        assertTrue(info.jdbcUrl().startsWith("jdbc:mysql://localhost:3306/orders"), info.jdbcUrl());
    }
}
