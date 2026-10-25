package io.floci.az.services.deployments;

/**
 * A template that cannot be parsed or evaluated. {@link #code()} is the ARM error code the deployment answers with;
 * {@value #INVALID_TEMPLATE} is Azure's code for a template syntax or evaluation error
 * ({@code troubleshooting/common-deployment-errors.md}).
 */
public class TemplateException extends RuntimeException {

    public static final String INVALID_TEMPLATE = "InvalidTemplate";

    private final String code;

    public TemplateException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** A template error with Azure's {@value #INVALID_TEMPLATE} code. */
    public static TemplateException invalid(String message) {
        return new TemplateException(INVALID_TEMPLATE, message);
    }

    public String code() {
        return code;
    }
}
