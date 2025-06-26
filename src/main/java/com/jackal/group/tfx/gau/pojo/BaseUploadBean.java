package com.jackal.group.tfx.gau.pojo;

import com.jackal.group.tfx.gau.enums.SourceType;
import lombok.Data;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@Data
public class BaseUploadBean {
    public String fileContent;
    public SourceType type;
    public S3FileMetaData metaData;
    public boolean isAttachment;
    public Paths attachmentPath;
}