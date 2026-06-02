package io.springperf.web.core.arg.databinder;

import io.springperf.web.http.WebServerHttpRequest;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.multipart.MultipartFile;

public class PerfDataBinder extends WebDataBinder {
    public PerfDataBinder(Object target, String objectName) {
        super(target, objectName);
        setFieldDefaultPrefix(null);
        setFieldMarkerPrefix(null);
    }

    public static void bind(WebServerHttpRequest request, WebDataBinder binder) {
        if (binder instanceof PerfDataBinder) {
            ((PerfDataBinder) binder).bind(request);
        } else {
            binder.bind(new MutablePropertyValues(request.getParameterMapArray()));
        }
    }

    public void bind(WebServerHttpRequest request) {
        MutablePropertyValues mpvs = new MutablePropertyValues(request.getParameterMapArray());
        MultiValueMap<String, MultipartFile> multiFileMap = request.getMultiFileMap();
        if (multiFileMap != null) {
            bindMultipart(multiFileMap, mpvs);
        }
        addBindValues(mpvs, request);
        doBind(mpvs);
    }

    protected void addBindValues(MutablePropertyValues mpvs, WebServerHttpRequest request) {

    }
}