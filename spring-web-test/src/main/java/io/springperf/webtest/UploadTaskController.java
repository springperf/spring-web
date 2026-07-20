package io.springperf.webtest;

import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/upload")
public class UploadTaskController {

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "ok");
        return result;
    }

    @PostMapping(value = "/db-req", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadDbReq(UploadTaskDbReq req) {
        Map<String, Object> result = new HashMap<>();
        result.put("projectId", req.getProjectId());
        result.put("seq", req.getSeq());
        result.put("dbVersionList", req.getDbVersionList());
        result.put("fileOriginalFilename", req.getFile() != null ? req.getFile().getOriginalFilename() : null);
        result.put("fileSize", req.getFile() != null ? req.getFile().getSize() : null);
        return result;
    }

    @PostMapping(value = "/db-req-validated", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadDbReqValidated(@Validated UploadTaskDbReq req) {
        Map<String, Object> result = new HashMap<>();
        result.put("projectId", req.getProjectId());
        result.put("seq", req.getSeq());
        result.put("dbVersionList", req.getDbVersionList());
        result.put("fileOriginalFilename", req.getFile() != null ? req.getFile().getOriginalFilename() : null);
        result.put("fileSize", req.getFile() != null ? req.getFile().getSize() : null);
        return result;
    }
}