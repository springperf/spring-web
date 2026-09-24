package io.springperf.web.core.mapping.match;

import java.util.*;

import org.springframework.http.MediaType;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;

public class ConsumeOrProduceMatcher implements Matcher {

    private final boolean isProduce;

    private final List<MediaTypeExpressionSupport> mediaTypeRuleList;

    public ConsumeOrProduceMatcher(boolean isProduce, List<MediaTypeExpressionSupport> mediaTypeRuleList) {
        this.isProduce = isProduce;
        this.mediaTypeRuleList = mediaTypeRuleList;
    }

    /** 是否 produces 约束（false = consumes 约束）——供不匹配原因判定（415/406）使用。 */
    public boolean isProduce() {
        return isProduce;
    }

    public List<MediaTypeExpressionSupport> getMediaTypeExpressions() {
        // 只读视图（合并方 MappingRegistry 只做拷贝读取）；字段为 null 时保持原行为
        return mediaTypeRuleList == null ? null : java.util.Collections.unmodifiableList(mediaTypeRuleList);
    }

    public List<MediaType> getProducibleMediaTypes() {
        if (!isProduce) {
            return null;
        }
        Set<MediaType> result = new LinkedHashSet<>();
        for (MediaTypeExpressionSupport expression : this.mediaTypeRuleList) {
            if (!expression.isNegated()) {
                result.add(expression.getMediaType());
            }
        }
        return new ArrayList<>(result);
    }

    private static final List<MediaType> DEFAULT_ACCEPT = Collections.singletonList(MediaType.ALL);

    @Override
    public boolean match(WebServerHttpRequest req, PathMappingContext mappingContext) {
        if (isProduce) {
            List<MediaType> mediaTypes = req.getHeaders().getAccept();
            List<MediaType> acceptedMediaTypes = CollectionUtils.isEmpty(mediaTypes) ? DEFAULT_ACCEPT : mediaTypes;
            for (MediaTypeExpressionSupport x : mediaTypeRuleList) {
                if (checkAccept(x, acceptedMediaTypes)) {
                    return true;
                }
            }
            return false;
        } else {
            MediaType mediaType = req.getHeaders().getContentType();
            MediaType contentType = mediaType != null ? mediaType : MediaType.APPLICATION_OCTET_STREAM;
            for (MediaTypeExpressionSupport x : mediaTypeRuleList) {
                if (checkContentType(x, contentType)) {
                    return true;
                }
            }
            return false;
        }
    }

    public boolean checkContentType(MediaTypeExpressionSupport expressionSupport, MediaType contentType) {
        boolean match = expressionSupport.mediaType.includes(contentType);
        return !expressionSupport.negated == match;
    }

    public boolean checkAccept(MediaTypeExpressionSupport expressionSupport, List<MediaType> acceptedMediaTypes) {
        MediaType ruleMediaType = expressionSupport.mediaType;
        boolean match = false;
        for (MediaType acceptedMediaType : acceptedMediaTypes) {
            if (ruleMediaType.isCompatibleWith(acceptedMediaType)
                    && matchParameters(ruleMediaType, acceptedMediaType)) {
                match = true;
                break;
            }
        }
        return !expressionSupport.negated == match;
    }

    private boolean matchParameters(MediaType ruleMediaType, MediaType acceptedMediaType) {
        for (String name : ruleMediaType.getParameters().keySet()) {
            String s1 = ruleMediaType.getParameter(name);
            String s2 = acceptedMediaType.getParameter(name);
            if (StringUtils.hasText(s1) && StringUtils.hasText(s2) && !s1.equalsIgnoreCase(s2)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isSameTypeMatcher(Matcher matcher) {
        if (matcher instanceof ConsumeOrProduceMatcher) {
            ConsumeOrProduceMatcher consumeOrProduceMatcher = (ConsumeOrProduceMatcher) matcher;
            return consumeOrProduceMatcher.isProduce == isProduce;
        }
        return false;
    }

    @Override
    public boolean haveAmbiguous(Matcher matcher) {
        if (matcher instanceof ConsumeOrProduceMatcher) {
            ConsumeOrProduceMatcher consumeOrProduceMatcher = (ConsumeOrProduceMatcher) matcher;
            if (consumeOrProduceMatcher.isProduce != isProduce) {
                return false;
            }
            for (MediaTypeExpressionSupport expressionSupport1 : mediaTypeRuleList) {
                for (MediaTypeExpressionSupport expressionSupport2 : consumeOrProduceMatcher.mediaTypeRuleList) {
                    if (expressionSupport1.mediaType.includes(expressionSupport2.mediaType)
                            || expressionSupport2.mediaType.includes(expressionSupport1.mediaType)) {
                        if (expressionSupport1.negated == expressionSupport2.negated) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return (isProduce ? "produces: " : "consumes: ")
                + (mediaTypeRuleList.size() == 1 ? mediaTypeRuleList.get(0) : mediaTypeRuleList);
    }
}
