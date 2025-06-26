package com.jackal.group.tfx.gau.pojo;

import lombok.Data;

@Data
public class S3FileMetaData {
    private String fileName;
    private String fileRecordId;
    private String title;
    private String source;
    private String originalPath;
    private String owner;
    private String categories;
    private String valueStream;
    private int level;
    private String attachmentType;
} 