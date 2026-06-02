package io.springperf.web.core.mapping.optimize;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.route.Router;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.util.PathPatternUtils;
import io.springperf.web.util.WebUtils;

import java.util.*;
import java.util.stream.Collectors;

public class PrefixPathRouterOptimizer implements RouterOptimizer {

    private final Map<String, Router> routeMap = new HashMap<>();

    private int prefixPathIndex = -1;

    public boolean support(List<PathMappingContext> list) {
        int[] posCount = new int[30];
        Map<Integer, Set<String>> prefixPathMap = new HashMap<>();
        for (PathMappingContext mappingContext : list) {
            String pathRule = mappingContext.getPathRule();
            List<String> pathStrList = Arrays.stream(pathRule.split("/")).collect(Collectors.toList());
            if (pathStrList.size() > posCount.length) {
                posCount = Arrays.copyOf(posCount, pathStrList.size());
            }
            int index = 0;
            for (String subPath : pathStrList) {
                if (PathPatternUtils.pathHaveWildcard(subPath)) {
                    break;
                }
                index++;
            }
            posCount[index]++;
            Set<String> prefixPathSet = prefixPathMap.get(index);
            if (prefixPathSet == null) {
                prefixPathSet = new HashSet<>();
                prefixPathMap.put(index, prefixPathSet);
            }
            prefixPathSet.add(pathStrList.stream().limit(index).collect(Collectors.joining("/")));
        }
        int total = list.size();
        double minScore = (total - 1.5) * total + 2;
        for (int i = 0; i < posCount.length; i++) {
            int count = posCount[i];
            if (count == 0) {
                continue;
            }
            int hashKeyCount = prefixPathMap.get(i).size();
            double avgFindCount = (double) count / hashKeyCount;
            double score = avgFindCount * count + (total - count) * (total - count) + 0.5 * total;
            if (score < minScore) {
                minScore = score;
                prefixPathIndex = i - 1;
            }
        }
        return prefixPathIndex != -1;
    }

    @Override
    public boolean initAndRemove(PathMappingContext mappingContext) {
        String pathRule = mappingContext.getPathRule();
        int[] slashIndexList = WebUtils.findAllSlashIndices(pathRule);
        if (slashIndexList.length <= prefixPathIndex) {
            return false;
        }
        String prefixPath = pathRule.substring(0, slashIndexList[prefixPathIndex]);
        if (PathPatternUtils.pathHaveWildcard(prefixPath)) {
            return false;
        }
        FullPathRouterOptimizer.putWildcardUrl(routeMap, prefixPath, mappingContext);
        return true;
    }

    @Override
    public Router optimizeRoute(WebServerHttpRequest req) {
        String path = req.getPath();
        int[] slashIndexList = getSlashIndexList(req);
        if (prefixPathIndex >= slashIndexList.length) {
            return null;
        }
        String prefixPath = path.substring(0, slashIndexList[prefixPathIndex]);
        Router router = routeMap.get(prefixPath);
        return router;
    }

    private static final RequestAttribute<int[]> SLASH_INDEX_LIST_ATTRIBUTE = RequestAttribute.createAttribute(int[].class);

    protected static int[] getSlashIndexList(WebServerHttpRequest req) {
        int[] slashIndexList = req.getRequestContext().getAttribute(SLASH_INDEX_LIST_ATTRIBUTE);
        if (slashIndexList == null) {
            slashIndexList = WebUtils.findAllSlashIndices(req.getPath());
            req.getRequestContext().setAttribute(SLASH_INDEX_LIST_ATTRIBUTE, slashIndexList);
        }
        return slashIndexList;
    }
}
