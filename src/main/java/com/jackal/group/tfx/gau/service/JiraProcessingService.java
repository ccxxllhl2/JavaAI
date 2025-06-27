package com.jackal.group.tfx.gau.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.jackal.group.tfx.gau.controller.JiraMarkdownController;
import com.jackal.group.tfx.gau.enums.SourceType;
import com.jackal.group.tfx.gau.pojo.BaseUploadBean;
import com.jackal.group.tfx.gau.pojo.S3FileMetaData;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Jira Processing Service
 * Handles conversion of Jira API responses to Markdown format
 * Supports both single and multiple task processing with S3 integration
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JiraProcessingService {
    
    private final ObjectMapper objectMapper;
    
    @Autowired
    @Lazy
    private JiraMarkdownController jiraMarkdownController;
    
    @Autowired
    @Lazy
    private S3Service s3Service;
    
    // S3 async upload queue and thread pool
    private final ConcurrentLinkedDeque<S3UploadTask> s3UploadQueue = new ConcurrentLinkedDeque<>();
    private ScheduledExecutorService s3UploadExecutor;
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private final AtomicLong totalProcessed = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);
    private volatile boolean shutdown = false;
    
    /**
     * Convert Jira API JSON response to Markdown format
     * 
     * @param jsonResponse JSON string returned by Jira API
     * @return Converted Markdown content, returns null when conversion fails
     * @throws JsonProcessingException Thrown when JSON parsing fails
     */
    public String convertToMarkdown(String jsonResponse) throws JsonProcessingException {
        if (jsonResponse == null || jsonResponse.trim().isEmpty()) {
            log.warn("JSON response is empty, cannot convert");
            return null;
        }
        
        try {
            JsonNode rootNode = objectMapper.readTree(jsonResponse);
            
            // Check if it's Jira search result format
            if (rootNode.has("issues")) {
                return procTaskAndReturnMarkdown(rootNode);
            } else {
                log.warn("Unsupported JSON format, expected issues array");
                return null;
            }
            
        } catch (JsonProcessingException e) {
            log.error("JSON parsing failed: {}", e.getMessage());
            throw e;
        }
    }
    
    /**
     * Convert Jira API JSON response to Markdown format (without sub tasks)
     * 
     * @param jsonResponse JSON string returned by Jira API
     * @return Converted Markdown content, returns null when conversion fails
     * @throws JsonProcessingException Thrown when JSON parsing fails
     */
    public String convertToMarkdownWithoutSubTasks(String jsonResponse) throws JsonProcessingException {
        if (jsonResponse == null || jsonResponse.trim().isEmpty()) {
            log.warn("JSON response is empty, cannot convert");
            return null;
        }
        
        try {
            JsonNode rootNode = objectMapper.readTree(jsonResponse);
            
            // Check if it's Jira search result format
            if (rootNode.has("issues")) {
                return procTaskAndReturnMarkdownWithoutSubTasks(rootNode);
            } else {
                log.warn("Unsupported JSON format, expected issues array");
                return null;
            }
            
        } catch (JsonProcessingException e) {
            log.error("JSON parsing failed: {}", e.getMessage());
            throw e;
        }
    }
    
    /**
     * Process task data and return Markdown content
     * Processing method is determined by number of issues
     * 
     * @param jsonResult JSON root node
     * @return Markdown content string, returns null when processing fails
     */
    private String procTaskAndReturnMarkdown(JsonNode jsonResult) {
        try {
            JsonNode issuesNode = jsonResult.get("issues");
            if (issuesNode == null || !issuesNode.isArray()) {
                log.warn("Issues array does not exist or has wrong format");
                return null;
            }
            
            int issueCount = issuesNode.size();
            if (issueCount == 0) {
                log.warn("Issues array is empty");
                return null;
            }
            
            if (issueCount == 1) {
                // Single task: process with original logic (compatible with /jira/markdown interface)
                return processSingleTask(jsonResult);
            } else {
                // Multiple tasks: generate complete Markdown for each task
                return processMultipleTasks(issuesNode);
            }
            
        } catch (Exception e) {
            log.error("Task processing failed: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Process single task (original logic, maintain compatibility)
     */
    private String processSingleTask(JsonNode jsonResult) {
        try {
            JsonNode issuesNode = jsonResult.get("issues");
            JsonNode mainTask = issuesNode.get(0);
            
            if (mainTask == null || mainTask.isNull()) {
                log.warn("Main task does not exist or is null");
                return null;
            }
            
            JsonNode fields = mainTask.get("fields");
            if (fields == null || fields.isNull()) {
                log.warn("Main task fields field does not exist or is null");
                return null;
            }
            
            String fileName = getTextValue(mainTask, "key");
            
            // Create main task info
            TaskInfo taskInfo = createTaskInfo(fields);
            
            // Create attachment list
            List<AttachmentInfo> attachList = createAttachmentList(fields);
            
            // Create sub task list
            List<SubTaskInfo> subTaskList = createSubTaskList(jsonResult);
            
            // Generate and return Markdown content
            String markdownContent = createMarkdownFile(taskInfo, attachList, subTaskList);
            
            log.info("Successfully converted single task: {} to Markdown format", fileName);
            
            return markdownContent;
            
        } catch (Exception e) {
            log.error("Single task processing failed: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Process multiple tasks, generate complete Markdown for each task
     */
    private String processMultipleTasks(JsonNode issuesNode) {
        StringBuilder allMarkdownContent = new StringBuilder();
        List<BaseUploadBean> s3UploadBeans = new ArrayList<>();
        
        try {
            for (int i = 0; i < issuesNode.size(); i++) {
                JsonNode currentTask = issuesNode.get(i);
                if (currentTask == null || currentTask.isNull()) {
                    log.warn("Skip empty task at index {}", i);
                    continue;
                }
                
                JsonNode fields = currentTask.get("fields");
                if (fields == null || fields.isNull()) {
                    log.warn("Task at index {} has no fields field, skipping", i);
                    continue;
                }
                
                String taskKey = getTextValue(currentTask, "key");
                log.info("Start processing task: {}", taskKey);
                
                try {
                    // 1. Create current task info
                    TaskInfo taskInfo = createTaskInfo(fields);
                    
                    // 2. Create attachment list
                    List<AttachmentInfo> attachList = createAttachmentList(fields);
                    
                    // 3. Create sub task list (extracted from current task's JSON)
                    List<SubTaskInfo> subTaskList = new ArrayList<>();
                    
                    // 4. Generate current task's Markdown content
                    String taskMarkdown = createMarkdownFileForSingleTask(taskInfo, attachList, subTaskList);
                    
                    // 5. Prepare S3 upload data
                    List<BaseUploadBean> taskUploadBeans = createS3UploadBeans(taskInfo, taskMarkdown, taskKey);
                    s3UploadBeans.addAll(taskUploadBeans);
                    
                    // 6. Add to total Markdown content
                    if (i > 0) {
                        allMarkdownContent.append("\n\n---\n\n"); // Separator between tasks
                    }
                    allMarkdownContent.append(taskMarkdown);
                    
                    log.info("Successfully processed task: {}", taskKey);
                    
                } catch (Exception e) {
                    log.error("Exception occurred while processing task {}: {}", taskKey, e.getMessage(), e);
                    // Add error info to Markdown
                    if (i > 0) {
                        allMarkdownContent.append("\n\n---\n\n");
                    }
                    allMarkdownContent.append(String.format("# Error\nException occurred while processing task %s: %s\n", taskKey, e.getMessage()));
                }
            }
            
            // Async push to S3
            if (!s3UploadBeans.isEmpty()) {
                submitToS3UploadQueue(s3UploadBeans);
            }
            
            log.info("Successfully processed {} tasks for Markdown conversion", issuesNode.size());
            
            return allMarkdownContent.toString();
            
        } catch (Exception e) {
            log.error("Multiple tasks processing failed: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Process task data and return Markdown content (without sub tasks)
     */
    private String procTaskAndReturnMarkdownWithoutSubTasks(JsonNode jsonResult) {
        try {
            JsonNode issuesNode = jsonResult.get("issues");
            if (issuesNode == null || !issuesNode.isArray() || issuesNode.size() == 0) {
                log.warn("Issues array is empty or does not exist");
                return null;
            }
            
            // Get main task (first issue)
            JsonNode mainTask = issuesNode.get(0);
            
            if (mainTask == null || mainTask.isNull()) {
                log.warn("Main task does not exist or is null");
                return null;
            }
            
            JsonNode fields = mainTask.get("fields");
            if (fields == null || fields.isNull()) {
                log.warn("Main task fields field does not exist or is null");
                return null;
            }
            
            String fileName = getTextValue(mainTask, "key");
            
            // Create main task info
            TaskInfo taskInfo = createTaskInfo(fields);
            
            // Create attachment list
            List<AttachmentInfo> attachList = createAttachmentList(fields);
            
            // Generate and return Markdown content (without sub tasks)
            String markdownContent = createMarkdownFileWithoutSubTasks(taskInfo, attachList);
            
            log.info("Successfully converted task: {} to Markdown format (without sub tasks)", fileName);
            
            return markdownContent;
            
        } catch (Exception e) {
            log.error("Task processing failed: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Create Markdown content for single task
     */
    private String createMarkdownFileForSingleTask(TaskInfo taskInfo, List<AttachmentInfo> attachList, List<SubTaskInfo> subTaskList) {
        StringBuilder markdown = new StringBuilder();
        
        // 1. Current task info
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
                .append(safeguardString(taskInfo.getAcceptanceCriteriaFieldName())).append("\n");
        
        // 2. Attachment info
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
        
        // 3. Sub task info
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
     * Create task info
     */
    private TaskInfo createTaskInfo(JsonNode fields) {
        TaskInfo taskInfo = new TaskInfo();
        
        // Basic info - ensure all fields have default empty string values
        taskInfo.setSummary(getTextValue(fields, "summary"));
        
        // Process description, remove empty lines and join
        String description = getTextValue(fields, "description");
        if (description != null && !description.trim().isEmpty()) {
            taskInfo.setDescription(description.lines()
                    .filter(line -> !line.trim().isEmpty())
                    .collect(Collectors.joining(" ")));
        } else {
            taskInfo.setDescription("");
        }
        
        // Status - safely handle nested objects
        JsonNode statusNode = fields.get("status");
        if (statusNode != null && !statusNode.isNull() && statusNode.has("name")) {
            JsonNode nameNode = statusNode.get("name");
            taskInfo.setStatus(nameNode != null && !nameNode.isNull() ? nameNode.asText("") : "");
        } else {
            taskInfo.setStatus("");
        }
        
        // Update time
        taskInfo.setUpdated(getTextValue(fields, "updated"));
        
        // Issue type - safely handle nested objects
        JsonNode issueTypeNode = fields.get("issuetype");
        if (issueTypeNode != null && !issueTypeNode.isNull() && issueTypeNode.has("name")) {
            JsonNode nameNode = issueTypeNode.get("name");
            taskInfo.setIssuetype(nameNode != null && !nameNode.isNull() ? nameNode.asText("") : "");
        } else {
            taskInfo.setIssuetype("");
        }
        
        // Labels - safely handle arrays
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
        
        // Custom field - Acceptance Criteria (customfield_27708)
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
        
        // Custom field - Market Affected (customfield_26615)
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
     * Create attachment list
     */
    private List<AttachmentInfo> createAttachmentList(JsonNode fields) {
        List<AttachmentInfo> attachList = new ArrayList<>();
        
        JsonNode attachmentNode = fields.get("attachment");
        if (attachmentNode != null && !attachmentNode.isNull() && attachmentNode.isArray()) {
            for (JsonNode attachment : attachmentNode) {
                if (attachment != null && !attachment.isNull()) {
                    AttachmentInfo info = new AttachmentInfo();
                    info.setFileId(getTextValue(attachment, "id"));
                    info.setFileName(getTextValue(attachment, "filename"));
                    info.setCreated(getTextValue(attachment, "created"));
                    info.setSize(getTextValue(attachment, "size"));
                    info.setUrl(getTextValue(attachment, "content"));
                    attachList.add(info);
                }
            }
        }
        
        return attachList;
    }
    
    /**
     * Create sub task list
     */
    private List<SubTaskInfo> createSubTaskList(JsonNode jsonResult) {
        List<SubTaskInfo> subTaskList = new ArrayList<>();
        
        JsonNode issuesNode = jsonResult.get("issues");
        if (issuesNode != null && issuesNode.isArray() && issuesNode.size() > 1) {
            // Skip first one (main task), process remaining sub tasks
            for (int i = 1; i < issuesNode.size(); i++) {
                JsonNode issue = issuesNode.get(i);
                if (issue != null && !issue.isNull()) {
                    SubTaskInfo subTask = new SubTaskInfo();
                    
                    // Safely handle summary field
                    JsonNode fields = issue.get("fields");
                    if (fields != null && !fields.isNull()) {
                        subTask.setSummary(getTextValue(fields, "summary"));
                    } else {
                        subTask.setSummary("");
                    }
                    
                    // Use safe getTextValue method
                    subTask.setKey(getTextValue(issue, "key"));
                    subTask.setUrl(getTextValue(issue, "self"));
                    
                    subTaskList.add(subTask);
                }
            }
        }
        
        return subTaskList;
    }
    
    /**
     * Create Markdown file
     */
    private String createMarkdownFile(TaskInfo taskInfo, List<AttachmentInfo> attachList, List<SubTaskInfo> subTaskList) {
        StringBuilder markdown = new StringBuilder();
        
        // Main task template - ensure all fields are not null
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
                .append(safeguardString(taskInfo.getAcceptanceCriteriaFieldName())).append("\n");
        
        // Attachment section
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
        
        // Sub task section
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
     * Create Markdown file without sub tasks
     */
    private String createMarkdownFileWithoutSubTasks(TaskInfo taskInfo, List<AttachmentInfo> attachList) {
        StringBuilder markdown = new StringBuilder();
        
        // Main task template - ensure all fields are not null
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
                .append(safeguardString(taskInfo.getAcceptanceCriteriaFieldName())).append("\n");
        
        // Attachment section
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
        
        return markdown.toString();
    }
    
    /**
     * Get text value from JSON node
     */
    private String getTextValue(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || !node.has(fieldName)) {
            return "";
        }
        
        JsonNode fieldNode = node.get(fieldName);
        if (fieldNode == null || fieldNode.isNull()) {
            return "";
        }
        
        return fieldNode.asText("");
    }
    
    /**
     * Safeguard string to prevent null values
     */
    private String safeguardString(String value) {
        return value == null ? "" : value;
    }
    
    /**
     * Create S3 upload beans
     */
    private List<BaseUploadBean> createS3UploadBeans(TaskInfo taskInfo, String markdownContent, String taskKey) {
        List<BaseUploadBean> uploadBeans = new ArrayList<>();
        
        try {
            // Use UUID7 to generate unique ID
            String uuid7 = Generators.timeBasedEpochGenerator().generate().toString();
            
            // Create metadata object
            S3FileMetaData metaData = new S3FileMetaData();
            metaData.setLevel(1);
            metaData.setTitle(safeguardString(taskInfo.getSummary()));
            metaData.setFileName(taskKey + ".md");
            metaData.setFileRecordId(uuid7);
            metaData.setTaskKey(taskKey);
            metaData.setStatus(safeguardString(taskInfo.getStatus()));
            metaData.setUpdated(safeguardString(taskInfo.getUpdated()));
            metaData.setIssuetype(safeguardString(taskInfo.getIssuetype()));
            metaData.setLabels(safeguardString(taskInfo.getLabels()));
            metaData.setMarketAffectedFieldName(safeguardString(taskInfo.getMarketAffectedFieldName()));
            metaData.setAcceptanceCriteriaFieldName(safeguardString(taskInfo.getAcceptanceCriteriaFieldName()));
            
            // 1. Create Markdown file upload object
            BaseUploadBean markdownBean = new BaseUploadBean();
            markdownBean.setFileContent(markdownContent);
            markdownBean.setFileName(String.format("jira/%s/%s.md", taskKey, uuid7));
            markdownBean.setType(SourceType.JIRA_IWPB);
            markdownBean.setMetaData(metaData);
            uploadBeans.add(markdownBean);
            
            // 2. Create metadata file upload object
            try {
                // Convert metadata to JSON string
                String metadataJson = objectMapper.writeValueAsString(metaData);
                
                BaseUploadBean metadataBean = new BaseUploadBean();
                metadataBean.setFileContent(metadataJson);
                metadataBean.setFileName(String.format("jira/%s/%s.metadata", taskKey, uuid7));
                metadataBean.setType(SourceType.JIRA_IWPB);
                
                // Create separate metadata for metadata file
                S3FileMetaData metadataForMetadata = new S3FileMetaData();
                metadataForMetadata.setLevel(1);
                metadataForMetadata.setTitle("Metadata for " + safeguardString(taskInfo.getSummary()));
                metadataForMetadata.setFileName(taskKey + ".metadata");
                metadataForMetadata.setFileRecordId(uuid7);
                metadataForMetadata.setTaskKey(taskKey);
                
                metadataBean.setMetaData(metadataForMetadata);
                uploadBeans.add(metadataBean);
                
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize metadata to JSON, task: {}, error: {}", taskKey, e.getMessage());
            }
            
        } catch (Exception e) {
            log.error("Failed to create S3 upload beans for task: {}, error: {}", taskKey, e.getMessage());
        }
        
        return uploadBeans;
    }
    
    /**
     * Async push to S3 (original @Async method)
     */
    @Async
    public CompletableFuture<Void> asyncPushToS3(List<BaseUploadBean> uploadBeans) {
        try {
            log.info("Start async push of {} tasks to S3", uploadBeans.size());
            s3Service.pushAll(uploadBeans);
            log.info("Successfully pushed {} tasks to S3", uploadBeans.size());
        } catch (Exception e) {
            log.error("Failed to push tasks to S3: {}", e.getMessage(), e);
            throw e;
        }
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Submit to S3 upload queue (new queue-based method)
     */
    public void submitToS3UploadQueue(List<BaseUploadBean> uploadBeans) {
        if (uploadBeans == null || uploadBeans.isEmpty()) {
            return;
        }
        
        // Group by task Key
        Map<String, List<BaseUploadBean>> taskGroups = uploadBeans.stream()
                .collect(Collectors.groupingBy(bean -> extractTaskKeyFromPath(bean.getFileName())));
        
        for (Map.Entry<String, List<BaseUploadBean>> entry : taskGroups.entrySet()) {
            String taskKey = entry.getKey();
            List<BaseUploadBean> taskBeans = entry.getValue();
            
            S3UploadTask task = new S3UploadTask(taskKey, taskBeans);
            s3UploadQueue.offer(task);
            queueSize.incrementAndGet();
            
            log.debug("Task {} submitted to S3 upload queue, queue size: {}", taskKey, queueSize.get());
        }
    }
    
    /**
     * Extract task key from file path
     */
    private String extractTaskKeyFromPath(String filePath) {
        if (filePath == null || !filePath.contains("/")) {
            return "unknown";
        }
        
        String[] parts = filePath.split("/");
        if (parts.length >= 2) {
            return parts[1]; // jira/{taskKey}/{fileName}
        }
        
        return "unknown";
    }
    
    /**
     * Get S3 upload queue statistics
     */
    public String getS3UploadQueueStats() {
        return String.format("Queue Size: %d, Total Processed: %d, Total Errors: %d",
                queueSize.get(), totalProcessed.get(), totalErrors.get());
    }
    
    /**
     * S3 Upload Task
     */
    @Data
    public static class S3UploadTask {
        private final String taskKey;
        private final List<BaseUploadBean> uploadBeans;
        private int retryCount = 0;
        private final long createTime = System.currentTimeMillis();
        
        public S3UploadTask(String taskKey, List<BaseUploadBean> uploadBeans) {
            this.taskKey = taskKey;
            this.uploadBeans = uploadBeans;
        }
        
        public void incrementRetry() {
            this.retryCount++;
        }
    }
    
    // Data class definitions
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
    
    @PostConstruct
    public void initS3UploadService() {
        // Create dedicated S3 upload thread pool
        s3UploadExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "S3-Upload-Worker");
            t.setDaemon(true);
            return t;
        });
        
        // Start S3 upload processor threads
        startS3UploadProcessors();
        
        log.info("S3 upload service started, core threads: 2");
    }
    
    @PreDestroy
    public void shutdownS3UploadService() {
        shutdown = true;
        
        if (s3UploadExecutor != null) {
            s3UploadExecutor.shutdown();
            try {
                if (!s3UploadExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    s3UploadExecutor.shutdownNow();
                    if (!s3UploadExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                        log.warn("S3 upload service did not terminate gracefully");
                    }
                }
            } catch (InterruptedException e) {
                s3UploadExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        log.info("S3 upload service shut down, total processed: {}, total errors: {}", totalProcessed.get(), totalErrors.get());
    }
    
    /**
     * Start S3 upload processors
     */
    private void startS3UploadProcessors() {
        // Start 2 processor threads
        for (int i = 0; i < 2; i++) {
            s3UploadExecutor.submit(this::processS3UploadQueue);
        }
    }
    
    /**
     * Process S3 upload queue
     */
    private void processS3UploadQueue() {
        while (!shutdown) {
            try {
                S3UploadTask task = s3UploadQueue.poll();
                if (task != null) {
                    queueSize.decrementAndGet();
                    processS3UploadTask(task);
                } else {
                    Thread.sleep(100); // Short sleep when queue is empty
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                totalErrors.incrementAndGet();
                log.error("Exception occurred while processing S3 upload queue: {}", e.getMessage(), e);
            }
        }
    }
    
    /**
     * Process S3 upload task
     */
    private void processS3UploadTask(S3UploadTask task) {
        try {
            log.debug("Start processing S3 upload task: {}, queue size: {}", task.getTaskKey(), queueSize.get());
            
            // Batch upload, use S3Service's pushAll method
            s3Service.pushAll(task.getUploadBeans());
            
            totalProcessed.incrementAndGet();
            log.info("Successfully uploaded {} files for task {} to S3", task.getUploadBeans().size(), task.getTaskKey());
            
        } catch (Exception e) {
            totalErrors.incrementAndGet();
            log.error("Failed to upload task {} to S3: {}", task.getTaskKey(), e.getMessage(), e);
            
            // Retry logic can be implemented here
            if (task.getRetryCount() < 3) {
                task.incrementRetry();
                
                // Delayed retry
                s3UploadExecutor.schedule(() -> {
                    s3UploadQueue.offer(task);
                    queueSize.incrementAndGet();
                    log.info("Task {} will retry in 5 seconds, current retry count: {}", task.getTaskKey(), task.getRetryCount());
                }, 5, TimeUnit.SECONDS);
            } else {
                log.error("Task {} failed after 3 retries, giving up upload", task.getTaskKey());
            }
        }
    }
} 