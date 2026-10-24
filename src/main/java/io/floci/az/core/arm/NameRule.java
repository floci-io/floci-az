package io.floci.az.core.arm;

import java.util.regex.Pattern;

/**
 * The syntax a type's resource names must follow ({@code resource-name-rules.md}), checked on PUT by
 * {@link ResourceNameRule} under {@code parity-overrides.strict-names}, or in every mode for a rule
 * {@link #inEveryMode() checked in every mode}. {@link #NONE} checks nothing.
 *
 * @param minLength the shortest name
 * @param maxLength the longest name
 * @param allowed   the whole name must match it
 * @param code      the error code a name outside the rule is answered with
 * @param message   the error message, with {@code %s} for the name
 * @param everyMode whether the rule is checked without {@code strict-names} too: for a type whose handler has always
 *                  refused names outside it, so the refusal keeps coming before a global name is reserved
 */
public record NameRule(int minLength, int maxLength, Pattern allowed, String code, String message, boolean everyMode) {

    /** No rule: every name passes. */
    public static final NameRule NONE = new NameRule(0, Integer.MAX_VALUE, Pattern.compile(".*", Pattern.DOTALL), "", "");

    /** Azure Cache for Redis: 1 to 63 alphanumerics and hyphens, starting and ending with one, no {@code --}. */
    public static final NameRule REDIS = new NameRule(1, 63,
        Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9]|-(?!-))*(?<!-)"), "InvalidResourceName",
        "The resource name '%s' is invalid: a cache name is 1 to 63 letters, digits and hyphens, starts and ends "
            + "with a letter or digit, and has no consecutive hyphens.");

    /** A rule checked only under {@code strict-names}. */
    public NameRule(int minLength, int maxLength, Pattern allowed, String code, String message) {
        this(minLength, maxLength, allowed, code, message, false);
    }

    /** This rule, checked in every mode. */
    public NameRule inEveryMode() {
        return new NameRule(minLength, maxLength, allowed, code, message, true);
    }

    /** Whether {@code name} follows the rule. */
    public boolean accepts(String name) {
        return name != null && name.length() >= minLength && name.length() <= maxLength
            && allowed.matcher(name).matches();
    }

    /** The error message for {@code name}. */
    public String message(String name) {
        return String.format(message, name);
    }
}
