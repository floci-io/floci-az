package fixtures;

import com.example.Config;

import javax.annotation.processing.Generated;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Every exemption the rules make. tools/checkstyle/test-rules.sh requires this file to pass; it is never compiled.
 */
@Generated("fixture")
public class Accepted {

    record Node(Object left, Object right) {
    }

    // A record pattern's components may use var.
    String pattern(Object value) {
        if (value instanceof Node(var left, var right)) {
            return left + "/" + right;
        }
        // A qualified name inside a string literal is data, as in a JMX operation signature.
        return "createQueue(java.lang.String)";
    }

    // java.util.List is imported, so the other List has to be qualified.
    java.awt.List awtList;
    List<String> names;
    Map<String, Integer> sizes;

    // A chain that starts with a variable is a field access, not a package.
    int max(Config config) {
        return config.limits.MAX;
    }

    Object checked(Object value) {
        return requireNonNull(value);
    }
}
