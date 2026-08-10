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
        if (slashIndexList.length <= suffixPathIndex || suffixPathIndex == 0) {
            return false;
        }
        // suffixPathIndex 从末尾锚定尾部非通配段数：后缀起始 slash 应为 倒数第 suffixPathIndex 个。
        // 修复前误用 slashIndexList[suffixPathIndex - 1]（从头数），substring 必含通配段，routeMap 永不填充。
        String suffixPath = pathRule.substring(slashIndexList[slashIndexList.length - suffixPathIndex]);
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
        // suffixPathIndex <= 0：尾部即通配段，无后缀可摘（且 length - suffixPathIndex 会越界）；必须显式拒绝
        if (suffixPathIndex <= 0 || suffixPathIndex >= slashIndexList.length) {
            return null;
        }
        // 与 initAndRemove 对称：查询 key 取请求路径倒数第 suffixPathIndex 段
        String suffixPath = path.substring(slashIndexList[slashIndexList.length - suffixPathIndex]);
        Router router = routeMap.get(suffixPath);
        return router;
    }
}
