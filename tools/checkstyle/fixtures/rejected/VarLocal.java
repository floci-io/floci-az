// expect: NoVar
package fixtures;

public class VarLocal {
    int count() {
        var count = 1;
        return count;
    }
}
