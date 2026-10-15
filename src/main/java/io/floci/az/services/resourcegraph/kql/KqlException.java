package io.floci.az.services.resourcegraph.kql;

/** A query the engine cannot parse or run; {@link #code()} is the Resource Graph error detail code. */
public final class KqlException extends RuntimeException {

    private final String code;

    public KqlException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static KqlException parse(String message) {
        return new KqlException("ParserFailure", message);
    }

    public static KqlException semantic(String message) {
        return new KqlException("SemanticError", message);
    }

    public String code() {
        return code;
    }
}
