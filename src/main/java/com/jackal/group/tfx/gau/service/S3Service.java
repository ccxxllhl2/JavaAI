package com.jackal.group.tfx.gau.service;

import com.jackal.group.tfx.gau.pojo.BaseUploadBean;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface S3Service {
    CompletableFuture<List<String>> listObjects(String bucketName);
    CompletableFuture<Void> uploadLocalFile(String bucketName, String keyName, Path filePath);
    CompletableFuture<Void> push(BaseUploadBean baseUploadBean);
    CompletableFuture<Void> downloadFile(String bucketName, String keyName, Path downloadPath);
    public void pushAll(Collection<BaseUploadBean> beans);
    CompletableFuture<Void> deleteFile(String bucketName, String keyName);
}