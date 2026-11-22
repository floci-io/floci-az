// expect: AvoidStarImport
package fixtures;

import static java.util.Objects.*;

public class StaticStarImport {
    Object checked(Object value) {
        return requireNonNull(value);
    }
}
