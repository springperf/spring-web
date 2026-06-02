package io.springperf.web.core.mapping.optimize;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.route.Router;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.util.PathPatternUtils;
import io.springperf.web.util.WebUtils;

import java.util.*;
import java.util.stream.Collectors;


public class SuffixPathRouterOptimizer implements RouterOptimizer {

    private final Map<String, Router> routeMap = new HashMap<>();

    private int suffixPathIndex = -1;

    public boolean support(List<PathMappingContext> wildcardPathList) {
        int[] reversedPosCount = new int[100];
        Map<Integer, Set<String>> suffixPathMap = new HashMap<>();
        for (PathMappingContext mappingContext : wildcardPathList) {
            String pathRule = mappingContext.getPathRule();
            List<String> pathStrList = Arrays.stream(pathRule.split("/")).collect(Collectors.toList());
            if (pathStrList.size() > reversedPosCount.length) {
                reversedPosCount = Arrays.copyOf(reversedPosCount, pathStrList.size());
            }
            int reversedIndex = 0;
            for (int i = pathStrList.size() - 1; i >= 0; i--) {
                String subPath = pathStrList.get(i);
                if (PathPatternUtils.pathHaveWildcard(subPath)) {
                    break;
                }
                reversedIndex++;
            }
            reversedPosCount[reversedIndex]++;
            Set<String> suffixPathSet = suffixPathMap.get(reversedIndex);
            if (suffixPathSet == null) {
                suffixPathSet = new HashSet<>();
                suffixPathMap.put(reversedIndex, suffixPathSet);
            }
            suffixPathSet.add(pathStrList.subList(pathStrList.size() - reversedIndex, pathStrList.size()).stream().collect(Collectors.joining("/")));
        }
        int total = wildcardPathList.size();
        double minScore = (total - 1.5) * total + 2;
        for (int i = 0; i < reversedPosCount.length; i++) {
            int count = reversedPosCount[i];
            if (count == 0) {
                continue;
            }
            int hashKeyCount = suffixPathMap.get(i).size();
            double avgFindCount = (double) count / hashKeyCount;
            double score = avgFindCount * count + (total - count) * (total - count) + 0.5 * total;
            if (score < minScore) {
                minScore = score;
                suffixPathIndex = i;
            }
        }
        return suffixPathIndex != -1;
    }

    @Override
    public boolean initAndRemove(PathMappingContext mappingContext) {
        String pathRule = mappingContext.getPathRule();
        int[] slashIndexList = WebUtils.findAllSlashIndices(pathRule);
        if (slashIndexList.length <= suffixPathIndex) {
            return false;
        }
        String suffixPath = pathRule.substring(slashIndexList[suffixPathIndex - 1]);
        if (PathPatternUtils.pathHaveWildcard(suffixPath)) {
            return false;
        }
        FullPathRouterOptimizer.putWildcardUrl(routeMap, suffixPath, mappingContext);
        return true;
    }

    @Override
    public Router optimizeRoute(WebServerHttpRequest req) {
        String path = req.getPath();
        int[] slashIndexList = PrefixPathRouterOptimizer.getSlashIndexList(req);
        String suffixPath = path.substring(slashIndexList[suffixPathIndex]);
        Router router = routeMap.get(suffixPath);
        return router;
    }
}
