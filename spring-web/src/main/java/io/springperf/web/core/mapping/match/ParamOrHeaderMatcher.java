package io.springperf.web.core.mapping.match;

import java.util.List;
import java.util.Objects;

import org.springframework.util.ObjectUtils;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;

public class ParamOrHeaderMatcher implements Matcher {

    private final boolean isHeader;

    private final List<NameValueExpressionSupport> expressionList;

    public ParamOrHeaderMatcher(boolean isHeader, List<NameValueExpressionSupport> expressionList) {
        this.isHeader = isHeader;
        this.expressionList = expressionList;
    }

    @Override
    public boolean match(WebServerHttpRequest req, PathMappingContext mappingContext) {
        for (NameValueExpressionSupport x : expressionList) {
            if (!checkExpression(x, req)) {
                return false;
            }
        }
        return true;
    }

    private boolean checkExpression(NameValueExpressionSupport expressionSupport, WebServerHttpRequest req) {
        boolean isMatch;
        if (isHeader) {
            if (expressionSupport.getValue() != null) {
                isMatch = ObjectUtils.nullSafeEquals(expressionSupport.getValue(),
                        req.getHeaders().getFirst(expressionSupport.getName()));
            } else {
                // Spring 7 移除了 HttpHeaders.containsKey（不再是 MultiValueMap）。改用 getFirst != null ——
                // 两版本通用，且与上面 value 分支的取值方式一致（空值头视为不匹配，符合路由条件语义）。
                isMatch = req.getHeaders().getFirst(expressionSupport.getName()) != null;
            }
        } else {
            if (expressionSupport.getValue() != null) {
                isMatch = ObjectUtils.nullSafeEquals(expressionSupport.getValue(),
                        req.getParameter(expressionSupport.getName()));
            } else {
                isMatch = req.getParameterMap().containsKey(expressionSupport.getName());
            }
        }
        return expressionSupport.negated != isMatch;
    }

    public boolean isHeader() {
        return isHeader;
    }

    public List<NameValueExpressionSupport> getExpressions() {
        // 只读视图（合并方 MappingRegistry 只做拷贝读取）；字段为 null 时保持原行为
        return expressionList == null ? null : java.util.Collections.unmodifiableList(expressionList);
    }

    @Override
    public boolean isSameTypeMatcher(Matcher matcher) {
        if (matcher instanceof ParamOrHeaderMatcher) {
            ParamOrHeaderMatcher paramOrHeaderMatcher = (ParamOrHeaderMatcher) matcher;
            return paramOrHeaderMatcher.isHeader == isHeader;
        }
        return false;
    }

    @Override
    public boolean haveAmbiguous(Matcher matcher) {
        if (matcher instanceof ParamOrHeaderMatcher) {
            ParamOrHeaderMatcher paramOrHeaderMatcher = (ParamOrHeaderMatcher) matcher;
            if (paramOrHeaderMatcher.isHeader != isHeader) {
                return false;
            }
            for (NameValueExpressionSupport expressionSupport1 : expressionList) {
                for (NameValueExpressionSupport expressionSupport2 : paramOrHeaderMatcher.expressionList) {
                    if (!Objects.equals(expressionSupport1.name, expressionSupport2.name)) {
                        continue;
                    }
                    boolean haveAmbiguous = false;
                    if (expressionSupport1.value == null && expressionSupport2.value == null) {
                        haveAmbiguous = expressionSupport1.negated == expressionSupport2.negated;
                    } else if (expressionSupport1.value == null) {
                        haveAmbiguous = !expressionSupport1.negated;
                    } else if (expressionSupport2.value == null) {
                        haveAmbiguous = !expressionSupport2.negated;
                    } else if (Objects.equals(expressionSupport1.value, expressionSupport2.value)) {
                        haveAmbiguous = expressionSupport1.negated == expressionSupport2.negated;
                    }
                    if (haveAmbiguous) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return (isHeader ? "headers: " : "params: ")
                + (expressionList.size() == 1 ? expressionList.get(0) : expressionList);
    }
}
