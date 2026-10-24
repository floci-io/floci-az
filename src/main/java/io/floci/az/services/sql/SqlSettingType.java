package io.floci.az.services.sql;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The singleton settings Azure SQL keeps on a server or a database: child resources with one fixed name, which
 * exist from the moment their parent does. A GET of one never written answers its defaults, a PUT replaces its
 * writable properties, and a PATCH (where the spec declares one) merges them.
 *
 * <p>Shapes and names come from the {@code Microsoft.Sql} 2023-08-01-preview Swagger (the api-version azurerm 4.x
 * and 5.x pin; azurerm 3.x pins 2023-02-01-preview, whose shapes for these are the same). Each fixed name is the
 * spec's single enum value for the name parameter, matched case-insensitively since azurerm sends
 * {@code geoBackupPolicies/default} where the spec declares {@code Default}.
 *
 * <p>Every PUT answers {@code 200}: the setting already exists, and {@code 200} is the one status every spec entry
 * and every azurerm client declares (the retention policies and the connection policy accept 200 or 202 only).
 */
enum SqlSettingType {

    /** {@code ServerConnectionPolicies.json}; "Default" is azurerm's {@code connection_policy} default. */
    CONNECTION_POLICY(Parent.SERVER, "connectionPolicies", "default", "Microsoft.Sql/servers/connectionPolicies",
        Map.of("connectionType", "Default"),
        Set.of("connectionType"), false),

    /**
     * {@code SqlVulnerabilityAssessmentsSettings.json}, Express vulnerability assessment; "Disabled" is azurerm's
     * {@code express_vulnerability_assessment_enabled = false}.
     */
    SQL_VULNERABILITY_ASSESSMENT(Parent.SERVER, "sqlVulnerabilityAssessments", "default",
        "Microsoft.Sql/servers/sqlVulnerabilityAssessments",
        Map.of("state", "Disabled"),
        Set.of("state"), false),

    /** {@code TransparentDataEncryptions.json}; a new database has TDE on (the spec example, azurerm's default). */
    TRANSPARENT_DATA_ENCRYPTION(Parent.DATABASE, "transparentDataEncryption", "current",
        "Microsoft.Sql/servers/databases/transparentDataEncryption",
        Map.of("state", "Enabled"),
        Set.of("state"), false),

    /**
     * {@code DatabaseSecurityAlertPolicies.json}; "Disabled" with no alerts addressed is what azurerm writes when no
     * {@code threat_detection_policy} is set. {@code storageAccountAccessKey} is accepted but never read back,
     * as azurerm expects ("the API does not return it for security reasons").
     */
    SECURITY_ALERT_POLICY(Parent.DATABASE, "securityAlertPolicies", "default",
        "Microsoft.Sql/servers/databases/securityAlertPolicies",
        ordered("state", "Disabled", "emailAccountAdmins", false, "retentionDays", 0),
        Set.of("state", "disabledAlerts", "emailAddresses", "emailAccountAdmins", "storageEndpoint",
            "retentionDays"), false),

    /**
     * {@code BackupShortTermRetentionPolicies.json}. 7 days is the spec example's and azurerm's default; 12 hours is
     * azurerm's {@code backup_interval_in_hours} default (the spec example shows 24, one of the two allowed values).
     */
    BACKUP_SHORT_TERM_RETENTION(Parent.DATABASE, "backupShortTermRetentionPolicies", "default",
        "Microsoft.Sql/servers/databases/backupShortTermRetentionPolicies",
        ordered("retentionDays", 7, "diffBackupIntervalInHours", 12),
        Set.of("retentionDays", "diffBackupIntervalInHours"), true),

    /**
     * {@code LongTermRetentionPolicies.json}: no long-term backups. azurerm reads {@code PT0S} as "none" and a
     * {@code weekOfYear} of 0 as unset.
     */
    BACKUP_LONG_TERM_RETENTION(Parent.DATABASE, "backupLongTermRetentionPolicies", "default",
        "Microsoft.Sql/servers/databases/backupLongTermRetentionPolicies",
        ordered("weeklyRetention", "PT0S", "monthlyRetention", "PT0S", "yearlyRetention", "PT0S", "weekOfYear", 0),
        Set.of("weeklyRetention", "monthlyRetention", "yearlyRetention", "weekOfYear", "makeBackupsImmutable"),
        false),

    /**
     * {@code GeoBackupPolicies.json}, read by azurerm for Data Warehouse and Free SKUs; "the default geo backup
     * policy configuration for a new resource is 'enabled'" ({@code mssql_database_resource.go}).
     */
    GEO_BACKUP_POLICY(Parent.DATABASE, "geoBackupPolicies", "Default",
        "Microsoft.Sql/servers/databases/geoBackupPolicies",
        Map.of("state", "Enabled"),
        Set.of("state"), false);

    /** The resource a setting belongs to. */
    enum Parent { SERVER, DATABASE }

    private final Parent parent;
    private final String segment;
    private final String fixedName;
    private final String armType;
    private final Map<String, Object> defaults;
    private final Set<String> writable;
    private final boolean patchable;

    SqlSettingType(Parent parent, String segment, String fixedName, String armType, Map<String, Object> defaults,
                   Set<String> writable, boolean patchable) {
        this.parent = parent;
        this.segment = segment;
        this.fixedName = fixedName;
        this.armType = armType;
        this.defaults = defaults;
        this.writable = writable;
        this.patchable = patchable;
    }

    /** The setting type of {@code parent} whose path segment is {@code segment}, ignoring case. */
    static Optional<SqlSettingType> of(Parent parent, String segment) {
        for (SqlSettingType type : values()) {
            if (type.parent == parent && type.segment.equalsIgnoreCase(segment)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    Parent parent() {
        return parent;
    }

    String segment() {
        return segment;
    }

    String fixedName() {
        return fixedName;
    }

    String armType() {
        return armType;
    }

    boolean patchable() {
        return patchable;
    }

    /** The key a stored setting is kept under on its parent's record. */
    String key() {
        return segment.toLowerCase(Locale.ROOT);
    }

    boolean isNamed(String name) {
        return fixedName.equalsIgnoreCase(name);
    }

    /** The properties of {@code stored}, or the defaults when it was never written. */
    Map<String, Object> properties(SqlState.SqlSetting stored) {
        return stored == null || stored.properties() == null ? defaults : stored.properties();
    }

    /**
     * The properties a write leaves: a PUT ({@code replace}) puts the body's writable properties over the defaults,
     * a PATCH over {@code current}. A property the type does not declare writable is dropped.
     */
    SqlState.SqlSetting write(Map<String, Object> current, Map<String, Object> body, boolean replace) {
        Map<String, Object> merged = new LinkedHashMap<>(replace ? defaults : current);
        body.forEach((name, value) -> {
            if (writable.contains(name) && value != null) {
                merged.put(name, value);
            }
        });
        return new SqlState.SqlSetting(merged);
    }

    private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }
}
