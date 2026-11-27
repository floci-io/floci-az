package io.floci.az.services.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Azure's normalizers, which make filters, sorting and facets on a string field ignore case or accents: the value
 * Solr holds for a filterable, sortable or facetable string and the string literals compared with it both pass
 * through the field's normalizer.
 *
 * <p>The predefined ones are Lucene's filters over the whole value, as Azure's are: {@code lowercase},
 * {@code uppercase}, {@code asciifolding}, {@code elision} and {@code standard} (lowercase, then asciifolding). A
 * custom normalizer of the index applies the token filters among those it names, in order; its char filters and any
 * other token filter are not applied. ASCII folding covers the Latin letters with diacritics and the common
 * ligatures, as Lucene's {@code ASCIIFoldingFilter} does for them.
 */
final class SearchNormalizers {

    /** Lucene's {@code ElisionFilter} articles for French, its default. */
    private static final List<String> ARTICLES = List.of("l", "m", "t", "qu", "n", "s", "j", "d", "c", "jusqu",
            "quoiqu", "lorsqu", "puisqu");
    private static final Map<Character, String> FOLDED = Map.ofEntries(
            Map.entry('ß', "ss"), Map.entry('Æ', "AE"), Map.entry('æ', "ae"), Map.entry('Ø', "O"),
            Map.entry('ø', "o"), Map.entry('Œ', "OE"), Map.entry('œ', "oe"), Map.entry('Ł', "L"),
            Map.entry('ł', "l"), Map.entry('Đ', "D"), Map.entry('đ', "d"), Map.entry('Þ', "TH"),
            Map.entry('þ', "th"), Map.entry('ı', "i"), Map.entry('Ħ', "H"), Map.entry('ħ', "h"));

    private SearchNormalizers() {
    }

    /** The normalizer an index's field names, or none: {@code null} or a name the index does not define. */
    static UnaryOperator<String> of(String name, Map<String, Object> definition) {
        if (name == null || name.isBlank()) {
            return UnaryOperator.identity();
        }
        UnaryOperator<String> predefined = filter(name);
        if (predefined != null) {
            return "standard".equalsIgnoreCase(name) ? value -> fold(value.toLowerCase(Locale.ROOT)) : predefined;
        }
        if (definition.get("normalizers") instanceof List<?> normalizers) {
            for (Object candidate : normalizers) {
                if (candidate instanceof Map<?, ?> custom && name.equals(custom.get("name"))) {
                    return custom(custom.get("tokenFilters"));
                }
            }
        }
        return UnaryOperator.identity();
    }

    private static UnaryOperator<String> custom(Object tokenFilters) {
        List<UnaryOperator<String>> chain = new ArrayList<>();
        if (tokenFilters instanceof List<?> names) {
            for (Object filterName : names) {
                UnaryOperator<String> filter = filterName instanceof String text ? filter(text) : null;
                if (filter != null) {
                    chain.add(filter);
                }
            }
        }
        return value -> {
            String result = value;
            for (UnaryOperator<String> filter : chain) {
                result = filter.apply(result);
            }
            return result;
        };
    }

    private static UnaryOperator<String> filter(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "lowercase", "standard" -> value -> value.toLowerCase(Locale.ROOT);
            case "uppercase" -> value -> value.toUpperCase(Locale.ROOT);
            case "asciifolding" -> SearchNormalizers::fold;
            case "elision" -> SearchNormalizers::elide;
            default -> null;
        };
    }

    /** Folds each letter outside Basic Latin to its ASCII letter, where it has one. */
    static String fold(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) {
                out.append(c);
                continue;
            }
            String special = FOLDED.get(c);
            if (special != null) {
                out.append(special);
                continue;
            }
            String decomposed = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
            char base = decomposed.charAt(0);
            // A letter with diacritics decomposes into its ASCII base and combining marks; anything else stays.
            out.append(base < 0x80 && decomposed.length() > 1 ? String.valueOf(base) : String.valueOf(c));
        }
        return out.toString();
    }

    /** Removes a leading French article and its apostrophe: {@code l'avion} is {@code avion}. */
    static String elide(String value) {
        int apostrophe = indexOfApostrophe(value);
        if (apostrophe > 0 && ARTICLES.contains(value.substring(0, apostrophe).toLowerCase(Locale.ROOT))) {
            return value.substring(apostrophe + 1);
        }
        return value;
    }

    private static int indexOfApostrophe(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '\'' || value.charAt(i) == '’') {
                return i;
            }
        }
        return -1;
    }
}
