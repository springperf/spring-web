package io.springperf.web.core.mapping.match;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;

public interface Matcher {

    boolean match(WebServerHttpRequest req, PathMappingContext mappingContext);

    boolean isSameTypeMatcher(Matcher matcher);

    boolean haveAmbiguous(Matcher matcher);
}


