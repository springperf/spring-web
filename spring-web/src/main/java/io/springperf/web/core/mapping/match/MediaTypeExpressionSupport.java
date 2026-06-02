package io.springperf.web.core.mapping.match;

import org.springframework.http.MediaType;

public class MediaTypeExpressionSupport {

    protected final MediaType mediaType;

    protected final boolean negated;

    public MediaTypeExpressionSupport(MediaType mediaType, boolean negated) {
        this.mediaType = mediaType;
        this.negated = negated;
    }

    public static MediaTypeExpressionSupport build(String mediaTypeStr) {
        boolean isNegated;
        if (mediaTypeStr.startsWith("!")) {
            isNegated = true;
            mediaTypeStr = mediaTypeStr.substring(1);
        } else {
            isNegated = false;
        }
        MediaType mediaType = MediaType.parseMediaType(mediaTypeStr);
        return new MediaTypeExpressionSupport(mediaType, isNegated);
    }

    public MediaType getMediaType() {
        return mediaType;
    }

    public boolean isNegated() {
        return negated;
    }

    @Override
    public String toString() {
        return negated ? "!" + mediaType.toString() : mediaType.toString();
    }
}
