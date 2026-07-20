package io.springperf.webtest;

import javax.validation.constraints.NotNull;
import org.springframework.web.multipart.MultipartFile;

public class UploadTaskDbReq {

    @NotNull
    private Long projectId;

    @NotNull
    private Integer seq;

    @NotNull
    private MultipartFile file;

    private String dbVersionList;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public MultipartFile getFile() {
        return file;
    }

    public void setFile(MultipartFile file) {
        this.file = file;
    }

    public String getDbVersionList() {
        return dbVersionList;
    }

    public void setDbVersionList(String dbVersionList) {
        this.dbVersionList = dbVersionList;
    }
}