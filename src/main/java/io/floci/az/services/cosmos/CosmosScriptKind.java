package io.floci.az.services.cosmos;

/**
 * The three server-side script resources a Cosmos DB container holds: stored procedures,
 * triggers and user-defined functions.
 *
 * <p>Each kind carries its path segment under {@code /dbs/{db}/colls/{coll}/}, the array key
 * of its feed response (the key every SDK reads, for example {@code r["StoredProcedures"]} in
 * the Python client) and the resource-type nibble the Java SDK's {@code ResourceId.tryParse}
 * reads from the upper half of the last byte of a 16-byte collection child {@code _rid}.</p>
 */
enum CosmosScriptKind {

    STORED_PROCEDURE("sprocs", "StoredProcedures", "stored procedure", 0x8),
    TRIGGER("triggers", "Triggers", "trigger", 0x7),
    USER_DEFINED_FUNCTION("udfs", "UserDefinedFunctions", "user defined function", 0x6);

    private final String segment;
    private final String feedKey;
    private final String displayName;
    private final int ridTypeNibble;

    CosmosScriptKind(String segment, String feedKey, String displayName, int ridTypeNibble) {
        this.segment = segment;
        this.feedKey = feedKey;
        this.displayName = displayName;
        this.ridTypeNibble = ridTypeNibble;
    }

    String segment() {
        return segment;
    }

    String feedKey() {
        return feedKey;
    }

    String displayName() {
        return displayName;
    }

    int ridTypeNibble() {
        return ridTypeNibble;
    }

    static CosmosScriptKind fromSegment(String segment) {
        for (CosmosScriptKind kind : values()) {
            if (kind.segment.equals(segment)) {
                return kind;
            }
        }
        return null;
    }
}
