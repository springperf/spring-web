package io.springperf.web.core.arg.provider;

import io.springperf.web.core.arg.resolver.MultiValueMapResolver;
import org.springframework.web.multipart.MultipartFile;

public class MultipartFileResolverProvider extends AbstractSupportTypeResolverProvider<MultipartFile> implements StaticArgumentResolverProvider {
    @Override
    protected Class<?> supportType() {
        return MultipartFile.class;
    }

    @Override
    protected MultiValueMapResolver<MultipartFile> getMultiValueMapResolver() {
        return ((parameter, mappingContext, request, response) -> request.getMultiFileMap());
    }
}
