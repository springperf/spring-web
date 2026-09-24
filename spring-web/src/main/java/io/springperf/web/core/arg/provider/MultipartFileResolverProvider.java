package io.springperf.web.core.arg.provider;

import org.springframework.web.multipart.MultipartFile;

import io.springperf.web.core.arg.resolver.MultiValueMapResolver;

public class MultipartFileResolverProvider extends AbstractSupportTypeResolverProvider<MultipartFile>
        implements StaticArgumentResolverProvider {
    @Override
    protected Class<?> supportType() {
        return MultipartFile.class;
    }

    @Override
    protected MultiValueMapResolver<MultipartFile> getMultiValueMapResolver() {
        return ((parameter, mappingContext, request, response) -> request.getMultiFileMap());
    }
}
