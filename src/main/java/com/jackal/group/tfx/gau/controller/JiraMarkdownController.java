package com.jackal.group.tfx.gau.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Jira数据到Markdown转换控制器
 * 用于将Jira API返回的JSON数据转换为Markdown格式
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JiraMarkdownController {
    
    private final ObjectMapper objectMapper;
    
    /**
     * 将Jira API返回的JSON字符串转换为Markdown格式并推送文件
     * 
     * @param jsonResponse Jira API返回的JSON字符串
     * @return 是否成功推送文件
     * @throws JsonProcessingException 当JSON解析失败时抛出
     */
    public boolean convertToMarkdown(String jsonResponse) throws JsonProcessingException {
        if (jsonResponse == null || jsonResponse.trim().isEmpty()) {
            log.warn("JSON响应为空，无法转换");
            return false;
        }
        
        try {
            JsonNode rootNode = objectMapper.readTree(jsonResponse);
            
            // 检查是否是Jira搜索结果格式
            if (rootNode.has("issues")) {
                return procTask(rootNode);
            } else {
                log.warn("不支持的JSON格式，期望包含issues数组");
                return false;
            }
            
        } catch (JsonProcessingException e) {
            log.error("JSON解析失败: {}", e.getMessage());
            throw e;
        }
    }
    
    /**
     * 处理任务数据，对应Python的proc_task函数
     * 
     * @param jsonResult JSON根节点
     * @return 是否处理成功
     */
    private boolean procTask(JsonNode jsonResult) {
        try {
            // 生成记录ID和文件名
            String recordId = UUID.randomUUID().toString();
            String mdFileName = recordId + ".md";
            
            JsonNode issuesNode = jsonResult.get("issues");
            if (issuesNode == null || !issuesNode.isArray() || issuesNode.size() == 0) {
                log.warn("issues数组为空或不存在");
                return false;
            }
            
            // 获取主任务（第一个issue）
            JsonNode mainTask = issuesNode.get(0);
            if (mainTask == null || mainTask.isNull()) {
                log.warn("主任务不存在或为null");
                return false;
            }
            
            JsonNode fields = mainTask.get("fields");
            if (fields == null || fields.isNull()) {
                log.warn("主任务fields字段不存在或为null");
                return false;
            }
            
            String fileName = getTextValue(mainTask, "key");
            
            // 创建主任务信息
            TaskInfo taskInfo = createTaskInfo(fields);
            
            // 创建附件列表
            List<AttachmentInfo> attachList = createAttachmentList(fields);
            
            // 创建子任务列表
            List<SubTaskInfo> subTaskList = createSubTaskList(jsonResult);
            
            // 生成Markdown内容
            String markdownContent = createMarkdownFile(taskInfo, attachList, subTaskList);
            
            // 创建Meta信息
            MetaInfo metaInfo = createMetaFile(taskInfo, recordId, mdFileName, getTextValue(mainTask, "self"));
            
            log.info("成功转换任务: {}, 文件名: {}", fileName, mdFileName);
            
            // TODO: 这里将来会实现文件推送逻辑
            // 目前直接返回true表示成功
            return true;
            
        } catch (Exception e) {
            log.error("处理任务失败: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 创建任务信息，对应Python的md_dict创建部分
     */
    private TaskInfo createTaskInfo(JsonNode fields) {
        TaskInfo taskInfo = new TaskInfo();
        
        // 基本信息 - 确保所有字段都有默认空字符串值
        taskInfo.setSummary(getTextValue(fields, "summary"));
        
        // 处理描述，移除空行并连接
        String description = getTextValue(fields, "description");
        if (description != null && !description.trim().isEmpty()) {
            taskInfo.setDescription(description.lines()
                    .filter(line -> !line.trim().isEmpty())
                    .collect(Collectors.joining(" ")));
        } else {
            taskInfo.setDescription("");
        }
        
        // 状态 - 安全处理嵌套对象
        JsonNode statusNode = fields.get("status");
        if (statusNode != null && !statusNode.isNull() && statusNode.has("name")) {
            JsonNode nameNode = statusNode.get("name");
            taskInfo.setStatus(nameNode != null && !nameNode.isNull() ? nameNode.asText("") : "");
        } else {
            taskInfo.setStatus("");
        }
        
        // 更新时间
        taskInfo.setUpdated(getTextValue(fields, "updated"));
        
        // 问题类型 - 安全处理嵌套对象
        JsonNode issueTypeNode = fields.get("issuetype");
        if (issueTypeNode != null && !issueTypeNode.isNull() && issueTypeNode.has("name")) {
            JsonNode nameNode = issueTypeNode.get("name");
            taskInfo.setIssuetype(nameNode != null && !nameNode.isNull() ? nameNode.asText("") : "");
        } else {
            taskInfo.setIssuetype("");
        }
        
        // 标签 - 安全处理数组
        JsonNode labelsNode = fields.get("labels");
        if (labelsNode != null && !labelsNode.isNull() && labelsNode.isArray()) {
            List<String> labels = new ArrayList<>();
            for (JsonNode label : labelsNode) {
                if (label != null && !label.isNull()) {
                    String labelText = label.asText("");
                    if (!labelText.trim().isEmpty()) {
                        labels.add(labelText);
                    }
                }
            }
            taskInfo.setLabels(String.join(", ", labels));
        } else {
            taskInfo.setLabels("");
        }
        
        // 自定义字段 - Acceptance Criteria (customfield_27708)
        JsonNode acField = fields.get("customfield_27708");
        if (acField != null && !acField.isNull()) {
            String acText = acField.asText("");
            if (!acText.trim().isEmpty()) {
                taskInfo.setAcceptanceCriteriaFieldName(acText.lines()
                        .filter(line -> !line.trim().isEmpty())
                        .collect(Collectors.joining("\n")));
            } else {
                taskInfo.setAcceptanceCriteriaFieldName("");
            }
        } else {
            taskInfo.setAcceptanceCriteriaFieldName("");
        }
        
        // 自定义字段 - Market Affected (customfield_26615)
        JsonNode maField = fields.get("customfield_26615");
        if (maField != null && !maField.isNull() && maField.isArray() && maField.size() > 0) {
            JsonNode firstItem = maField.get(0);
            if (firstItem != null && !firstItem.isNull() && firstItem.has("value")) {
                JsonNode valueNode = firstItem.get("value");
                taskInfo.setMarketAffectedFieldName(valueNode != null && !valueNode.isNull() ? valueNode.asText("") : "");
            } else {
                taskInfo.setMarketAffectedFieldName("");
            }
        } else {
            taskInfo.setMarketAffectedFieldName("");
        }
        
        return taskInfo;
    }
    
    /**
     * 创建附件列表
     */
    private List<AttachmentInfo> createAttachmentList(JsonNode fields) {
        List<AttachmentInfo> attachList = new ArrayList<>();
        
        JsonNode attachmentNode = fields.get("attachment");
        if (attachmentNode != null && attachmentNode.isArray()) {
            for (JsonNode attachment : attachmentNode) {
                AttachmentInfo info = new AttachmentInfo();
                info.setFileId(getTextValue(attachment, "id"));
                info.setFileName(getTextValue(attachment, "filename"));
                info.setCreated(getTextValue(attachment, "created"));
                info.setSize(getTextValue(attachment, "size"));
                info.setUrl(getTextValue(attachment, "content"));
                attachList.add(info);
            }
        }
        
        return attachList;
    }
    
    /**
     * 创建子任务列表
     */
    private List<SubTaskInfo> createSubTaskList(JsonNode jsonResult) {
        List<SubTaskInfo> subTaskList = new ArrayList<>();
        
        JsonNode issuesNode = jsonResult.get("issues");
        if (issuesNode != null && !issuesNode.isNull() && issuesNode.isArray() && issuesNode.size() > 1) {
            // 跳过第一个（主任务），处理其余的子任务
            for (int i = 1; i < issuesNode.size(); i++) {
                JsonNode subTask = issuesNode.get(i);
                if (subTask != null && !subTask.isNull()) {
                    JsonNode fields = subTask.get("fields");
                    
                    SubTaskInfo info = new SubTaskInfo();
                    
                    // 安全处理summary字段
                    if (fields != null && !fields.isNull() && fields.has("summary")) {
                        JsonNode summaryNode = fields.get("summary");
                        info.setSummary(summaryNode != null && !summaryNode.isNull() ? summaryNode.asText("") : "");
                    } else {
                        info.setSummary("");
                    }
                    
                    // 使用安全的getTextValue方法
                    info.setKey(getTextValue(subTask, "key"));
                    info.setUrl(getTextValue(subTask, "self"));
                    subTaskList.add(info);
                }
            }
        }
        
        return subTaskList;
    }
    
    /**
     * 创建Markdown文件内容，对应Python的create_md_file函数
     */
    private String createMarkdownFile(TaskInfo taskInfo, List<AttachmentInfo> attachList, List<SubTaskInfo> subTaskList) {
        StringBuilder markdown = new StringBuilder();
        
        // 主任务模板 - 确保所有字段都不为null
        markdown.append("# Summary\n")
                .append(safeguardString(taskInfo.getSummary())).append("\n")
                .append("## Description\n")
                .append(safeguardString(taskInfo.getDescription())).append("\n")
                .append("## Task Info\n")
                .append("* Status: ").append(safeguardString(taskInfo.getStatus())).append("\n")
                .append("* Updated: ").append(safeguardString(taskInfo.getUpdated())).append("\n")
                .append("* Issuetype: ").append(safeguardString(taskInfo.getIssuetype())).append("\n")
                .append("## Labels\n")
                .append(safeguardString(taskInfo.getLabels())).append("\n")
                .append("## Market Affected Field Name\n")
                .append(safeguardString(taskInfo.getMarketAffectedFieldName())).append("\n")
                .append("## Acceptance Criteria Field Name\n")
                .append(safeguardString(taskInfo.getAcceptanceCriteriaFieldName())).append("\n")
                .append("---\n");
        
        // 附件部分
        if (attachList != null && !attachList.isEmpty()) {
            markdown.append("## Attachment\n");
            for (AttachmentInfo attach : attachList) {
                if (attach != null) {
                    markdown.append(safeguardString(attach.getFileName())).append("\n")
                            .append("* ID: ").append(safeguardString(attach.getFileId())).append("\n")
                            .append("* Created: ").append(safeguardString(attach.getCreated())).append("\n")
                            .append("* File Size: ").append(safeguardString(attach.getSize())).append("\n")
                            .append("* Download URL: ").append(safeguardString(attach.getUrl())).append("\n")
                            .append("---\n");
                }
            }
        }
        
        // 子任务部分
        if (subTaskList != null && !subTaskList.isEmpty()) {
            markdown.append("## Sub Tasks\n");
            for (SubTaskInfo subTask : subTaskList) {
                if (subTask != null) {
                    markdown.append(safeguardString(subTask.getSummary())).append("\n")
                            .append("* Key: ").append(safeguardString(subTask.getKey())).append("\n")
                            .append("* URL: ").append(safeguardString(subTask.getUrl())).append("\n")
                            .append("---\n");
                }
            }
        }
        
        return markdown.toString();
    }
    
    /**
     * 创建Meta信息，对应Python的create_meta_file函数
     */
    private MetaInfo createMetaFile(TaskInfo taskInfo, String recordId, String mdFileName, String selfUrl) {
        MetaInfo metaInfo = new MetaInfo();
        metaInfo.setLevel(0);
        metaInfo.setTitle(safeguardString(taskInfo != null ? taskInfo.getSummary() : ""));
        metaInfo.setFileName(safeguardString(mdFileName));
        metaInfo.setFileRecordId(safeguardString(recordId));
        metaInfo.setFileOriginalName(safeguardString(taskInfo != null ? taskInfo.getSummary() : ""));
        metaInfo.setFileOriginalPath(safeguardString(selfUrl) + "/" + safeguardString(mdFileName));
        metaInfo.setSource("jira-iwpb");
        metaInfo.setValueStream("");
        metaInfo.setCategories("");
        metaInfo.setParentFileName(null);
        metaInfo.setAttachmentType("file");
        metaInfo.setOwner("");
        
        return metaInfo;
    }
    
    /**
     * 安全获取文本值的工具方法
     */
    private String getTextValue(JsonNode node, String fieldName) {
        if (node != null && !node.isNull() && node.has(fieldName)) {
            JsonNode fieldNode = node.get(fieldName);
            if (fieldNode != null && !fieldNode.isNull()) {
                return fieldNode.asText("");
            }
        }
        return "";
    }
    
    /**
     * 确保字符串不为null的工具方法
     * 如果字符串为null，返回空字符串
     */
    private String safeguardString(String value) {
        return value != null ? value : "";
    }
    
    /**
     * 检查是否应该转换为Markdown格式
     * 
     * @param contentType 响应的Content-Type
     * @return 是否应该转换
     */
    public boolean shouldConvertToMarkdown(String contentType) {
        // 可以根据需要添加更多判断条件
        return contentType != null && contentType.contains("application/json");
    }
    
    // 数据类定义
    
    @Data
    public static class TaskInfo {
        private String summary;
        private String description;
        private String status;
        private String updated;
        private String issuetype;
        private String labels;
        private String marketAffectedFieldName;
        private String acceptanceCriteriaFieldName;
    }
    
    @Data
    public static class AttachmentInfo {
        private String fileId;
        private String fileName;
        private String created;
        private String size;
        private String url;
    }
    
    @Data
    public static class SubTaskInfo {
        private String summary;
        private String key;
        private String url;
    }
    
    @Data
    public static class MetaInfo {
        private int level;
        private String title;
        private String fileName;
        private String fileRecordId;
        private String fileOriginalName;
        private String fileOriginalPath;
        private String source;
        private String valueStream;
        private String categories;
        private String parentFileName;
        private String attachmentType;
        private String owner;
    }
} 