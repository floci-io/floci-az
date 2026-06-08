package io.floci.az.services.appconfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Opaque token pagination matching Azure App Configuration's {@code After}/{@code @nextLink} model.
 *
 * <p>The continuation token is the base64 of the last-returned item's strictly-increasing sort token,
 * so the input list must be sorted such that {@code tokenFn} is ascending. Subsequent pages skip every
 * item whose token is {@code <=} the decoded {@code After} value.</p>
 */
public final class Paginator {

    public static final int PAGE_SIZE = 100;

    private Paginator() {
    }

    /**
     * @param sorted     items pre-sorted so that {@code tokenFn} is strictly ascending
     * @param after      the raw (base64) {@code After} token from the request, or null for the first page
     * @param pageSize   maximum items per page
     * @param tokenFn    maps an item to its strictly-increasing sort token
     * @param nextLinkFn builds the relative {@code @nextLink} from an encoded continuation token
     */
    public static AppConfigModels.Page paginate(
            List<Map<String, Object>> sorted,
            String after,
            int pageSize,
            Function<Map<String, Object>, String> tokenFn,
            Function<String, String> nextLinkFn) {

        int start = 0;
        if (after != null && !after.isBlank()) {
            String decoded = decode(after);
            while (start < sorted.size() && tokenFn.apply(sorted.get(start)).compareTo(decoded) <= 0) {
                start++;
            }
        }

        int end = Math.min(start + pageSize, sorted.size());
        List<Map<String, Object>> page = new ArrayList<>(sorted.subList(start, end));

        String nextLink = null;
        if (end < sorted.size()) {
            nextLink = nextLinkFn.apply(encode(tokenFn.apply(sorted.get(end - 1))));
        }
        return new AppConfigModels.Page(page, nextLink);
    }

    public static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static String decode(String token) {
        try {
            return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
