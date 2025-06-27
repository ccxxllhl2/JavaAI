package com.jackal.group.tfx.gau.pojo;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.Map;

@Data
public class S3FileMetaData {
    private int level;
    private String title;

    @JsonProperty("file_name")
    private String fileName;

    @JsonProperty("file_record_id")
    private String fileRecordId;

    @JsonProperty("file_original_name")
    private String fileOriginalName;

    @JsonProperty("file_original_path")
    private String fileOriginalPath;

    private String source;

    @JsonProperty("value_stream")
    private String valueStream;

    private String categories;

    @JsonProperty("parent_file_name")
    private String parentFileName;

    @JsonProperty("attachment_type")
    private String attachmentType;

    private String owner;
    @Override
    public String toString() {
        return "S3FileMetaData{" +
                "level=" + level +
                ", title='" + title + '\'' +
                ", fileName='" + fileName + '\'' +
                ", fileRecordId='" + fileRecordId + '\'' +
                ", fileOriginalName='" + fileOriginalName + '\'' +
                ", fileOriginalPath='" + fileOriginalPath + '\'' +
                ", source='" + source + '\'' +
                ", valueStream='" + valueStream + '\'' +
                ", categories='" + categories + '\'' +
                ", parentFileName='" + parentFileName + '\'' +
                ", attachmentType='" + attachmentType + '\'' +
                ", owner='" + owner + '\'' +
                '}';
    }

    public Map<String, String> convertToMap() {
        Map<String, String> map = new HashMap<>();

        map.put("level", String.valueOf(level));
        map.put("title", title != null ? title : "");
        map.put("fileName", fileName != null ? fileName : "");
        map.put("fileRecordId", fileRecordId != null ? fileRecordId : "");
        map.put("fileOriginalName", fileOriginalName != null ? fileOriginalName : "");
        map.put("fileOriginalPath", fileOriginalPath != null ? fileOriginalPath : "");
        map.put("source", source != null ? source : "");
        map.put("valueStream", valueStream != null ? valueStream : "");
        map.put("categories", categories != null ? categories : "");
        map.put("parentFileName", parentFileName != null ? parentFileName : "");
        map.put("attachmentType", attachmentType != null ? attachmentType : "");
        map.put("owner", owner != null ? owner : "");
        
        return map;
    }
} 