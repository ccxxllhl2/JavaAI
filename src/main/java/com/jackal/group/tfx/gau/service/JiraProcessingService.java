package com.jackal.group.tfx.gau.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Jira数据处理服务
 * 用于将Jira API返回的JSON数据转换为Markdown格式
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
    
    /**
     * 将Jira API返回的JSON字符串转换为Markdown格式
     * 
     * @param jsonResponse Jira API返回的JSON字符串
     * @return 转换后的Markdown内容，转换失败时返回null
     * @throws JsonProcessingException 当JSON解析失败时抛出
     */
    public String convertToMarkdown(String jsonResponse) throws JsonProcessingException {
        if (jsonResponse == null || jsonResponse.trim().isEmpty()) {
            log.warn("JSON响应为空，无法转换");
            return null;
        }
        
        try {
            JsonNode rootNode = objectMapper.readTree(jsonResponse);
            
            // 检查是否是Jira搜索结果格式
            if (rootNode.has("issues")) {
                return procTaskAndReturnMarkdown(rootNode);
            } else {
                log.warn("不支持的JSON格式，期望包含issues数组");
                return null;
            }
            
        } catch (JsonProcessingException e) {
            log.error("JSON解析失败: {}", e.getMessage());
            throw e;
        }
    }
    
    /**
     * 将Jira API返回的JSON字符串转换为Markdown格式（不包含子任务）
     * 
     * @param jsonResponse Jira API返回的JSON字符串
     * @return 转换后的Markdown内容，转换失败时返回null
     * @throws JsonProcessingException 当JSON解析失败时抛出
     */
    public String convertToMarkdownWithoutSubTasks(String jsonResponse) throws JsonProcessingException {
        if (jsonResponse == null || jsonResponse.trim().isEmpty()) {
            log.warn("JSON响应为空，无法转换");
            return null;
        }
        
        try {
            JsonNode rootNode = objectMapper.readTree(jsonResponse);
            
            // 检查是否是Jira搜索结果格式
            if (rootNode.has("issues")) {
                return procTaskAndReturnMarkdownWithoutSubTasks(rootNode);
            } else {
                log.warn("不支持的JSON格式，期望包含issues数组");
                return null;
            }
            
        } catch (JsonProcessingException e) {
            log.error("JSON解析失败: {}", e.getMessage());
            throw e;
        }
    }
    
    /**
     * 处理任务数据并返回Markdown内容
     * 根据issues数量决定处理方式
     * 
     * @param jsonResult JSON根节点
     * @return Markdown内容字符串，处理失败时返回null
     */
    private String procTaskAndReturnMarkdown(JsonNode jsonResult) {
        try {
            JsonNode issuesNode = jsonResult.get("issues");
            if (issuesNode == null || !issuesNode.isArray()) {
                log.warn("issues数组不存在或格式错误");
                return null;
            }
            
            int issueCount = issuesNode.size();
            if (issueCount == 0) {
                log.warn("issues数组为空");
                return null;
            }
            
            if (issueCount == 1) {
                // 单个任务：按原有逻辑处理（兼容/jira/markdown接口）
                return processSingleTask(jsonResult);
            } else {
                // 多个任务：每个任务都生成完整的Markdown
                return processMultipleTasks(issuesNode);
            }
            
        } catch (Exception e) {
            log.error("处理任务失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 处理单个任务（原有逻辑，保持兼容性）
     */
    private String processSingleTask(JsonNode jsonResult) {
        try {
            JsonNode issuesNode = jsonResult.get("issues");
            JsonNode mainTask = issuesNode.get(0);
            
            if (mainTask == null || mainTask.isNull()) {
                log.warn("主任务不存在或为null");
                return null;
            }
            
            JsonNode fields = mainTask.get("fields");
            if (fields == null || fields.isNull()) {
                log.warn("主任务fields字段不存在或为null");
                return null;
            }
            
            String fileName = getTextValue(mainTask, "key");
            
            // 创建主任务信息
            TaskInfo taskInfo = createTaskInfo(fields);
            
            // 创建附件列表
            List<AttachmentInfo> attachList = createAttachmentList(fields);
            
            // 创建子任务列表
            List<SubTaskInfo> subTaskList = createSubTaskList(jsonResult);
            
            // 生成并返回Markdown内容
            String markdownContent = createMarkdownFile(taskInfo, attachList, subTaskList);
            
            log.info("成功转换单个任务: {} 为Markdown格式", fileName);
            
            return markdownContent;
            
        } catch (Exception e) {
            log.error("处理单个任务失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 处理多个任务，每个任务生成完整的Markdown
     */
    private String processMultipleTasks(JsonNode issuesNode) {
        StringBuilder allMarkdownContent = new StringBuilder();
        List<BaseUploadBean> s3UploadBeans = new ArrayList<>();
        
        try {
            for (int i = 0; i < issuesNode.size(); i++) {
                JsonNode currentTask = issuesNode.get(i);
                if (currentTask == null || currentTask.isNull()) {
                    log.warn("跳过第{}个空任务", i);
                    continue;
                }
                
                JsonNode fields = currentTask.get("fields");
                if (fields == null || fields.isNull()) {
                    log.warn("第{}个任务的fields字段不存在，跳过", i);
                    continue;
                }
                
                String taskKey = getTextValue(currentTask, "key");
                log.info("开始处理任务: {}", taskKey);
                
                try {
                    // 1. 创建当前任务信息
                    TaskInfo taskInfo = createTaskInfo(fields);
                    
                    // 2. 创建附件列表
                    List<AttachmentInfo> attachList = createAttachmentList(fields);
                    
                    // 3. 获取子任务信息（调用/jira/subInfo接口）
                    List<SubTaskInfo> subTaskList = getSubTasksFromApi(taskKey);
                    
                    // 4. 生成当前任务的Markdown内容
                    String taskMarkdown = createMarkdownFileForSingleTask(taskInfo, attachList, subTaskList);
                    
                    // 5. 准备S3上传数据
                    BaseUploadBean uploadBean = createS3UploadBean(taskInfo, taskMarkdown, taskKey);
                    s3UploadBeans.add(uploadBean);
                    
                    // 6. 添加到总的Markdown内容中
                    if (i > 0) {
                        allMarkdownContent.append("\n\n---\n\n"); // 任务之间的分隔符
                    }
                    allMarkdownContent.append(taskMarkdown);
                    
                    log.info("成功处理任务: {}", taskKey);
                    
                } catch (Exception e) {
                    log.error("处理任务 {} 时发生异常: {}", taskKey, e.getMessage(), e);
                    // 添加错误信息到Markdown中
                    if (i > 0) {
                        allMarkdownContent.append("\n\n---\n\n");
                    }
                    allMarkdownContent.append(String.format("# Error\n处理任务 %s 时发生异常: %s\n", taskKey, e.getMessage()));
                }
            }
            
            // 异步推送到S3
            if (!s3UploadBeans.isEmpty()) {
                asyncPushToS3(s3UploadBeans);
            }
            
            log.info("成功处理 {} 个任务的Markdown转换", issuesNode.size());
            return allMarkdownContent.toString();
            
        } catch (Exception e) {
            log.error("处理多个任务失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 处理任务数据并返回Markdown内容（不包含子任务）
     * 
     * @param jsonResult JSON根节点
     * @return Markdown内容字符串，处理失败时返回null
     */
    private String procTaskAndReturnMarkdownWithoutSubTasks(JsonNode jsonResult) {
        try {
            JsonNode issuesNode = jsonResult.get("issues");
            if (issuesNode == null || !issuesNode.isArray() || issuesNode.size() == 0) {
                log.warn("issues数组为空或不存在");
                return null;
            }
            
            // 获取主任务（第一个issue）
            JsonNode mainTask = issuesNode.get(0);
            if (mainTask == null || mainTask.isNull()) {
                log.warn("主任务不存在或为null");
                return null;
            }
            
            JsonNode fields = mainTask.get("fields");
            if (fields == null || fields.isNull()) {
                log.warn("主任务fields字段不存在或为null");
                return null;
            }
            
            String fileName = getTextValue(mainTask, "key");
            
            // 创建主任务信息
            TaskInfo taskInfo = createTaskInfo(fields);
            
            // 创建附件列表
            List<AttachmentInfo> attachList = createAttachmentList(fields);
            
            // 生成并返回Markdown内容（不包含子任务）
            String markdownContent = createMarkdownFileWithoutSubTasks(taskInfo, attachList);
            
            log.info("成功转换任务: {} 为Markdown格式（无子任务）", fileName);
            
            return markdownContent;
            
        } catch (Exception e) {
            log.error("处理任务失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 通过API获取指定任务的子任务信息
     */
    private List<SubTaskInfo> getSubTasksFromApi(String parentTaskKey) {
        List<SubTaskInfo> subTaskList = new ArrayList<>();
        
        try {
            // 构造请求对象 - 这里需要根据实际情况设置jiraSource和jiraToken
            // 由于无法直接获取这些信息，暂时返回空列表
            // TODO: 需要找到合适的方式传递jiraSource和jiraToken
            log.warn("暂时无法获取任务 {} 的子任务信息，需要传递jiraSource和jiraToken", parentTaskKey);
            
            /* 
            // 理想情况下的实现：
            JiraMarkdownController.JiraSubInfoRequest request = new JiraMarkdownController.JiraSubInfoRequest();
            request.setJira_key(parentTaskKey);
            request.setJiraSource(jiraSource); // 需要传入
            request.setJiraToken(jiraToken);   // 需要传入
            
            ResponseEntity<List<JiraMarkdownController.SubTaskResult>> response = 
                jiraMarkdownController.getSubTaskInfo(request);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                for (JiraMarkdownController.SubTaskResult subTaskResult : response.getBody()) {
                    SubTaskInfo subTask = new SubTaskInfo();
                    subTask.setKey(subTaskResult.getKey());
                    subTask.setSummary(subTaskResult.getSummary());
                    subTask.setUrl(""); // API没有返回URL信息
                    subTaskList.add(subTask);
                }
            }
            */
            
        } catch (Exception e) {
            log.error("获取任务 {} 的子任务信息失败: {}", parentTaskKey, e.getMessage(), e);
        }
        
        return subTaskList;
    }
    
    /**
     * 为单个任务创建Markdown内容
     */
    private String createMarkdownFileForSingleTask(TaskInfo taskInfo, List<AttachmentInfo> attachList, List<SubTaskInfo> subTaskList) {
        StringBuilder markdown = new StringBuilder();
        
        // 1. 当前任务信息
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
        
        // 2. 附件信息
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
        
        // 3. 子任务信息
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
     * 创建任务信息
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
     * 创建Markdown文件内容
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
     * 创建Markdown文件内容（不包含子任务）
     */
    private String createMarkdownFileWithoutSubTasks(TaskInfo taskInfo, List<AttachmentInfo> attachList) {
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
        
        return markdown.toString();
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
     * 创建S3上传Bean对象
     */
    private BaseUploadBean createS3UploadBean(TaskInfo taskInfo, String markdownContent, String taskKey) {
        BaseUploadBean uploadBean = new BaseUploadBean();
        
        // 设置文件内容
        uploadBean.fileContent = markdownContent;
        
        // 设置源类型 (根据实际需求确定使用哪个类型)
        uploadBean.type = SourceType.JIRA_IWPB; // 可根据实际情况调整
        
        // 设置附件相关属性
        uploadBean.isAttachment = false;
        uploadBean.attachmentPath = null;
        
        // 创建元数据
        S3FileMetaData metaData = new S3FileMetaData();
        String recordId = UUID.randomUUID().toString();
        String fileName = recordId + ".md";
        
        metaData.setFileName(fileName);
        metaData.setFileRecordId(recordId);
        metaData.setTitle(safeguardString(taskInfo.getSummary()));
        metaData.setSource("jira-iwpb");
        metaData.setOriginalPath("jira/" + taskKey + "/" + fileName);
        metaData.setOwner("");
        metaData.setCategories("");
        metaData.setValueStream("");
        metaData.setLevel(0);
        metaData.setAttachmentType("file");
        
        uploadBean.metaData = metaData;
        
        return uploadBean;
    }
    
    /**
     * 异步推送到S3
     */
    @Async
    public CompletableFuture<Void> asyncPushToS3(List<BaseUploadBean> uploadBeans) {
        return CompletableFuture.runAsync(() -> {
            try {
                log.info("开始异步推送 {} 个任务到S3", uploadBeans.size());
                s3Service.pushAll(uploadBeans);
                log.info("成功推送 {} 个任务到S3", uploadBeans.size());
            } catch (Exception e) {
                log.error("推送任务到S3失败: {}", e.getMessage(), e);
            }
        });
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
} 