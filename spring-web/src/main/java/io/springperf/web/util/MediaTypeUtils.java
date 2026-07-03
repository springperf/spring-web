package io.springperf.web.util;

import org.springframework.http.MediaType;

import java.util.Comparator;
import java.util.List;

/**
 * Cross-version MediaType utilities.
 *
 * <p>Replaces Spring 6.x API removed in Spring 7.x (SB 4.x):
 * <ul>
 *   <li>{@code MediaType.sortBySpecificity()} → {@link #sortBySpecificity(List)}</li>
 *   <li>{@code MediaType.SPECIFICITY_COMPARATOR} → {@link #SPECIFICITY_COMPARATOR}</li>
 *   <li>{@code MediaType.APPLICATION_STREAM_JSON} → {@link #APPLICATION_STREAM_JSON}</li>
 * </ul>
 */
public abstract class MediaTypeUtils {

    /**
     * {@code "application/stream+json"} — 在 Spring 6.x 中为 {@code MediaType.APPLICATION_STREAM_JSON}，
     * Spring 7.x 中已移除，故在此定义兜底常量。
     */
    public static final MediaType APPLICATION_STREAM_JSON = new MediaType("application", "stream+json");

    /**
     * Compares two {@link MediaType}s by specificity (descending):
     * <ol>
     *   <li>More parameters first (more specific)</li>
     *   <li>Concrete type before wildcard type ({@code text/* < text/plain})</li>
     *   <li>Concrete subtype before wildcard subtype ({@code text/* < text/plain})</li>
     * </ol>
     */
    public static final Comparator<MediaType> SPECIFICITY_COMPARATOR = (a, b) -> {
        int paramsComp = Integer.compare(b.getParameters().size(), a.getParameters().size());
        if (paramsComp != 0) return paramsComp;
        if (!a.isWildcardType() && b.isWildcardType()) return -1;
        if (a.isWildcardType() && !b.isWildcardType()) return 1;
        if (!a.isWildcardSubtype() && b.isWildcardSubtype()) return -1;
        if (a.isWildcardSubtype() && !b.isWildcardSubtype()) return 1;
        int typeComp = a.getType().compareToIgnoreCase(b.getType());
        if (typeComp != 0) return typeComp;
        return a.getSubtype().compareToIgnoreCase(b.getSubtype());
    };

    /**
     * Compares two {@link MediaType}s by quality then specificity (descending):
     * <ol>
     *   <li>Higher {@code q} value first</li>
     *   <li>More parameters first (more specific)</li>
     *   <li>Alphabetically by type</li>
     *   <li>Alphabetically by subtype</li>
     * </ol>
     */
    public static final Comparator<MediaType> QUALITY_AND_SPECIFICITY_COMPARATOR = (a, b) -> {
        int qualityComp = Double.compare(b.getQualityValue(), a.getQualityValue());
        if (qualityComp != 0) return qualityComp;
        return SPECIFICITY_COMPARATOR.compare(a, b);
    };

    /**
     * Sort {@code mediaTypes} in-place by specificity descending.
     *
     * @see #SPECIFICITY_COMPARATOR
     */
    public static void sortBySpecificity(List<MediaType> mediaTypes) {
        mediaTypes.sort(SPECIFICITY_COMPARATOR);
    }

    /**
     * Compare two MediaTypes by specificity.
     *
     * @return {@code <= 0} if {@code a} is more or equally specific,
     * {@code > 0} if {@code b} is more specific
     * @see #SPECIFICITY_COMPARATOR
     */
    public static int compareSpecificity(MediaType a, MediaType b) {
        return SPECIFICITY_COMPARATOR.compare(a, b);
    }
}
