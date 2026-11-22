// expect: NoFqcn
package fixtures;

public class QualifiedStaticCall {
    Object checked(Object value) {
        return java.util.Objects.requireNonNull(value);
    }
}
