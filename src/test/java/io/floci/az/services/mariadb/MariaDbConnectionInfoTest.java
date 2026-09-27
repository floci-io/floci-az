package io.floci.az.services.mariadb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MariaDbConnectionInfo — connection string builder")
class MariaDbConnectionInfoTest {

    @Test
    @DisplayName("an IPv6 host is bracketed in URLs and bare for the CLI")
    void ipv6Host() {
        MariaDbConnectionInfo info = MariaDbConnectionInfo.of("::1", 3306, "admin", "Pass123!", "orders");
        assertTrue(info.jdbcUrl().startsWith("jdbc:mariadb://[::1]:3306/orders"), info.jdbcUrl());
        assertTrue(info.uri().startsWith("mariadb://admin:Pass123!@[::1]:3306/orders"), info.uri());
        assertTrue(info.mysql().contains("::1") && !info.mysql().contains("[::1]"), info.mysql());
    }

    @Test
    @DisplayName("a hostname is unchanged")
    void hostname() {
        MariaDbConnectionInfo info = MariaDbConnectionInfo.of("localhost", 3306, "admin", "Pass123!", "orders");
        assertTrue(info.jdbcUrl().startsWith("jdbc:mariadb://localhost:3306/orders"), info.jdbcUrl());
    }
}
