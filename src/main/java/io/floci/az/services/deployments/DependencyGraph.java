package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The order a deployment creates its resources in: every resource after the ones it depends on, otherwise in
 * template order. Azure's dependency rules ("Define the order for deploying resources in ARM templates"):
 *
 * <ul>
 *   <li>an explicit {@code dependsOn} entry names a resource by name, by {@code type/name}, or by id</li>
 *   <li>{@code reference()} and the {@code list*} functions create an implicit dependency when they name a
 *       resource by name; passing an id creates none, and {@code resourceId()} alone creates none</li>
 *   <li>a child resource gets no implicit dependency on its parent</li>
 * </ul>
 *
 * <p>A dependency on a resource the template does not declare, a name two resources share, and a cycle are each
 * an {@code InvalidTemplate}.
 */
public final class DependencyGraph {

    /**
     * One resource with its type, name and id evaluated.
     *
     * @param index     its position in the template
     * @param id        its ARM id
     * @param type      its type as the template spells it
     * @param name      its name as evaluated, slash-separated for a child
     * @param dependsOn what it depends on: evaluated {@code dependsOn} entries and implicit references by name
     */
    public record Node(int index, String id, String type, String name, List<String> dependsOn) {

        public Node {
            dependsOn = List.copyOf(dependsOn);
        }
    }

    private DependencyGraph() {
    }

    /** {@code nodes} in creation order. */
    public static List<Node> order(List<Node> nodes) {
        List<List<Integer>> edges = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            Set<Integer> targets = new LinkedHashSet<>();
            for (String reference : nodes.get(i).dependsOn()) {
                int target = resolve(nodes, reference, nodes.get(i));
                if (target != i) {
                    targets.add(target);
                }
            }
            edges.add(List.copyOf(targets));
        }
        List<Node> ordered = new ArrayList<>();
        int[] state = new int[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            visit(nodes, edges, i, state, ordered);
        }
        return ordered;
    }

    /**
     * The first argument of every {@code reference()} and {@code list*()} call in {@code value}, a resource
     * definition or any part of one: the expressions whose value, when it is a name rather than an id, is an
     * implicit dependency.
     */
    public static List<Expression> implicitReferences(JsonNode value) {
        List<Expression> references = new ArrayList<>();
        collect(value, references);
        return references;
    }

    /** True when an implicit reference's evaluated value names a resource rather than giving its id. */
    public static boolean isName(String reference) {
        return reference != null && !reference.startsWith("/");
    }

    private static void collect(JsonNode value, List<Expression> references) {
        if (value.isTextual() && ExpressionParser.isExpression(value.asText())) {
            walk(ExpressionParser.parse(value.asText()), references);
        } else if (value.isContainerNode()) {
            value.forEach(child -> collect(child, references));
        }
    }

    private static void walk(Expression expression, List<Expression> references) {
        switch (expression) {
            case Expression.FunctionCall call -> {
                String name = call.name().toLowerCase(Locale.ROOT);
                if ((name.equals("reference") || name.startsWith("list")) && !call.arguments().isEmpty()) {
                    references.add(call.arguments().getFirst());
                }
                call.arguments().forEach(argument -> walk(argument, references));
            }
            case Expression.PropertyAccess access -> walk(access.target(), references);
            case Expression.IndexAccess access -> {
                walk(access.target(), references);
                walk(access.index(), references);
            }
            case Expression.Literal ignored -> {
                // A literal calls nothing.
            }
        }
    }

    /** The index of the node {@code reference} names: by id, by {@code type/name}, or by name. */
    private static int resolve(List<Node> nodes, String reference, Node from) {
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            if (matches(nodes.get(i), reference)) {
                matches.add(i);
            }
        }
        if (matches.isEmpty()) {
            throw DeploymentTemplate.invalid("The resource '" + reference + "' that '" + from.name()
                + "' depends on is not defined in the template.");
        }
        if (matches.size() > 1) {
            throw DeploymentTemplate.invalid("The dependency '" + reference + "' of '" + from.name()
                + "' matches more than one resource; name it by type and name, or by id.");
        }
        return matches.getFirst();
    }

    private static boolean matches(Node node, String reference) {
        if (reference.startsWith("/")) {
            return node.id().equalsIgnoreCase(reference);
        }
        if (reference.contains("/")) {
            return (ArmResourcePath.normalizedType(node.type()) + "/" + node.name())
                .equalsIgnoreCase(ArmResourcePath.normalizedType(reference));
        }
        return node.name().equalsIgnoreCase(reference);
    }

    /** Depth-first post-order; {@code state} is 0 unvisited, 1 on the path, 2 done. */
    private static void visit(List<Node> nodes, List<List<Integer>> edges, int index, int[] state,
                              List<Node> ordered) {
        if (state[index] == 2) {
            return;
        }
        if (state[index] == 1) {
            throw DeploymentTemplate.invalid("Circular dependency detected on resource: '"
                + nodes.get(index).id() + "'.");
        }
        state[index] = 1;
        for (int target : edges.get(index)) {
            visit(nodes, edges, target, state, ordered);
        }
        state[index] = 2;
        ordered.add(nodes.get(index));
    }
}
