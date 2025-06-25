package com.jackal.group.tfx.gau.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jackal.group.tfx.gau.service.JiraProcessingService;
import com.jackal.group.tfx.gau.util.HttpUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Jira Markdown API 控制器
 * 提供批量查询Jira任务并转换为Markdown的功能
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class JiraMarkdownController {
    
    private final ObjectMapper objectMapper;
    private final JiraProcessingService jiraProcessingService;
    
    @Autowired
    private JiraController jiraController;
    
    /**
     * 批量查询Jira任务并转换为Markdown格式
     * 
     * @param request 包含jiraKeys、jiraSource和jiraToken的请求对象
     * @return 包含所有任务Markdown内容的列表
     */
    @PostMapping(value = "/jira/markdown", consumes = {"application/json"})
    public ResponseEntity<List<String>> batchConvertToMarkdown(@RequestBody JiraMarkdownRequest request) {
        try {
            // 参数验证
            if (request.getJiraKeys() == null || request.getJiraKeys().isEmpty()) {
                return ResponseEntity.status(400).body(null);
            }
            
            if (request.getJiraSource() == null) {
                return ResponseEntity.status(400).body(null);
            }
            
            if (request.getJiraToken() == null || request.getJiraToken().trim().isEmpty()) {
                return ResponseEntity.status(400).body(null);
            }
            
            List<String> markdownResults = new ArrayList<>();
            String apiPrefix = getApiPrefix(request.getJiraSource());
            
            // 遍历所有jiraKeys
            for (String jiraKey : request.getJiraKeys()) {
                if (jiraKey == null || jiraKey.trim().isEmpty()) {
                    log.warn("跳过空的jiraKey");
                    continue;
                }
                
                try {
                    // 构造JQL查询payload
                    JiraSearchRequest searchRequest = new JiraSearchRequest();
                    searchRequest.setApiPrefix(apiPrefix);
                    searchRequest.setApiVersion("2");
                    searchRequest.setToken(request.getJiraToken());
                    
                    // 构造JQL对象
                    JiraJqlQuery jqlQuery = new JiraJqlQuery();
                    jqlQuery.setStartAt(0);
                    jqlQuery.setMaxResults(100);
                    jqlQuery.setJql(String.format("(key=\"%s\") and issuetype in (\"W Programme\", Story, Epic)", jiraKey));
                    
                    searchRequest.setJql(objectMapper.writeValueAsString(jqlQuery));
                    
                    // 调用JiraController的/jira/jql接口
                    String requestBody = objectMapper.writeValueAsString(searchRequest);
                    ResponseEntity response = jiraController.searchJira(requestBody, false, false);
                    
                    if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                        String jsonResponse = response.getBody().toString();
                        
                        // 转换为Markdown格式（不包含子任务）
                        String markdownContent = jiraProcessingService.convertToMarkdownWithoutSubTasks(jsonResponse);
                        
                        if (markdownContent != null) {
                            markdownResults.add(markdownContent);
                            log.info("成功转换任务 {} 为Markdown格式", jiraKey);
                        } else {
                            log.warn("任务 {} 转换为Markdown失败", jiraKey);
                            // 可以选择添加错误信息到结果中
                            markdownResults.add(String.format("# Error\n无法转换任务 %s 为Markdown格式", jiraKey));
                        }
                    } else {
                        log.warn("查询任务 {} 失败，状态码: {}", jiraKey, response.getStatusCode());
                        markdownResults.add(String.format("# Error\n查询任务 %s 失败", jiraKey));
                    }
                    
                } catch (Exception e) {
                    log.error("处理任务 {} 时发生异常: {}", jiraKey, e.getMessage(), e);
                    markdownResults.add(String.format("# Error\n处理任务 %s 时发生异常: %s", jiraKey, e.getMessage()));
                }
            }
            
            return ResponseEntity.ok(markdownResults);
            
        } catch (Exception e) {
            log.error("批量转换Markdown失败: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(null);
        }
    }
    
    /**
     * 根据jiraSource获取API前缀
     * 
     * @param jiraSource Jira源
     * @return API前缀URL
     */
    private String getApiPrefix(JiraSource jiraSource) {
        switch (jiraSource) {
            case wpb:
                return "https://wpb.jira.com";
            case alm:
                return "https://alm.jira.com";
            default:
                throw new IllegalArgumentException("不支持的Jira源: " + jiraSource);
        }
    }
    
    // 请求和响应数据类
    
    @Data
    public static class JiraMarkdownRequest {
        private List<String> jiraKeys;
        private JiraSource jiraSource;
        private String jiraToken;
    }
    
    @Data
    public static class JiraSearchRequest {
        private String apiPrefix;
        private String apiVersion;
        private String token;
        private String jql;
    }
    
    @Data
    public static class JiraJqlQuery {
        private int startAt;
        private int maxResults;
        private String jql;
    }
    
    public enum JiraSource {
        wpb, alm
    }
} 