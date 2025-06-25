package com.jackal.group.tfx.gau.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jackal.group.tfx.gau.event.CacheEvent;
import com.jackal.group.tfx.gau.event.CacheEventService;
import com.jackal.group.tfx.gau.event.MessagePublisher;
import com.jackal.group.tfx.gau.service.JiraProcessingService;
import com.jackal.group.tfx.gau.util.CacheUtil;
import com.jackal.group.tfx.gau.util.HttpUtil;
import io.micrometer.common.util.StringUtils;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class JiraController {
    private final ObjectMapper objectMapper;
    private final CacheEventService cacheEventService;
    private final MessagePublisher messagePublisher;
    private final JiraProcessingService jiraProcessingService;

    @PostMapping(value = "/jira/jql", consumes = {"application/json", "text/plain"})
    public ResponseEntity searchJira(@RequestBody String body, 
                                   @RequestParam(required = false) Boolean useCache,
                                   @RequestParam(required = false) Boolean toMarkdown) {
        try {
            var req = objectMapper.readValue(body, SearchRequest.class);
            var rawKey = req.apiPrefix + "|" + req.jql;
            var cacheKey = CacheUtil.checksum(rawKey) + "." + rawKey.length();
            if (useCache != null && useCache) {
                var cachedResult = cacheEventService.tryCache(cacheKey);
                if (StringUtils.isNotBlank(cachedResult)) {
                    // 如果缓存结果存在且需要转换为Markdown
                    if (toMarkdown != null && toMarkdown) {
                        try {
                            String markdownContent = jiraProcessingService.convertToMarkdown(cachedResult);
                            if (markdownContent != null) {
                                return ResponseEntity.ok(markdownContent);
                            } else {
                                log.warn("转换缓存结果为Markdown失败，返回原始JSON");
                            }
                        } catch (JsonProcessingException e) {
                            log.warn("转换缓存结果为Markdown失败，返回原始JSON: {}", e.getMessage());
                        }
                    }
                    return ResponseEntity.ok(cachedResult);
                }
            }
            if (StringUtils.isBlank(req.apiPrefix) || StringUtils.isBlank(req.apiVersion) || StringUtils.isBlank(req.token) || req.jql == null) {
                return ResponseEntity.status(400).body("Please provide necessary fields");
            }

            var url = "%s/rest/api/%s/search".formatted(req.apiPrefix, req.apiVersion);
            var resp = HttpUtil.post(req.jql,
                Map.of("Authorization", "Bearer %s".formatted(req.token),
                       "Content-Type", "application/json"),
                url);
            if (HttpUtil.is2xxSuccessful(resp)) {
                String responseBody = resp.body();
                
                // 如果需要转换为Markdown格式
                if (toMarkdown != null && toMarkdown) {
                    try {
                        String markdownContent = jiraProcessingService.convertToMarkdown(responseBody);
                        if (markdownContent != null) {
                            // 缓存原始JSON响应
                            messagePublisher.publishMessage(CacheEvent.builder().key(cacheKey).rawKey(rawKey).details(responseBody).build());
                            // 返回Markdown内容
                            return ResponseEntity.ok(markdownContent);
                        } else {
                            log.warn("转换为Markdown失败，返回原始JSON");
                        }
                    } catch (JsonProcessingException e) {
                        log.warn("转换为Markdown失败，返回原始JSON: {}", e.getMessage());
                    }
                }
                
                messagePublisher.publishMessage(CacheEvent.builder().key(cacheKey).rawKey(rawKey).details(responseBody).build());
                return ResponseEntity.ok(responseBody);
            } else {
                return ResponseEntity.status(resp.getStatusCode()).body(resp.body());
            }
        } catch (JsonProcessingException e) {
            return ResponseEntity.status(400).body("Invalid request body");
        } catch (IOException|InterruptedException e) {
            return ResponseEntity.status(500).body("Failed to call Jira API: " + e.getMessage());
        }
    }

    @PostMapping(value = "/jira/proxy")
    public ResponseEntity<String> proxy(@RequestBody String body, 
                                      @RequestParam(required = false) Boolean useCache,
                                      @RequestParam(required = false) Boolean toMarkdown) {
        try {
            var req = objectMapper.readValue(body, JiraProxyRequest.class);
            var rawKey = req.url;
            var cacheKey = CacheUtil.checksum(rawKey) + "." + rawKey.length();
            if (useCache != null && useCache) {
                var cachedResult = cacheEventService.tryCache(cacheKey);
                if (StringUtils.isNotBlank(cachedResult)) {
                    // 如果缓存结果存在且需要转换为Markdown
                    if (toMarkdown != null && toMarkdown) {
                        try {
                            String markdownContent = jiraProcessingService.convertToMarkdown(cachedResult);
                            if (markdownContent != null) {
                                return ResponseEntity.ok(markdownContent);
                            } else {
                                log.warn("转换缓存结果为Markdown失败，返回原始JSON");
                            }
                        } catch (JsonProcessingException e) {
                            log.warn("转换缓存结果为Markdown失败，返回原始JSON: {}", e.getMessage());
                        }
                    }
                    return ResponseEntity.ok(cachedResult);
                }
            }
            if (StringUtils.isBlank(req.url) || StringUtils.isBlank(req.token)) {
                return ResponseEntity.status(400).body("Please provide necessary fields");
            }
            var resp = HttpUtil.getAsString(req.url, req.headers);
            if (HttpUtil.is2xxSuccessful(resp)) {
                String responseBody = resp.body();
                
                // 如果需要转换为Markdown格式
                if (toMarkdown != null && toMarkdown) {
                    try {
                        String markdownContent = jiraProcessingService.convertToMarkdown(responseBody);
                        if (markdownContent != null) {
                            // 缓存原始JSON响应
                            messagePublisher.publishMessage(CacheEvent.builder().key(cacheKey).rawKey(rawKey).details(responseBody).build());
                            // 返回Markdown内容
                            return ResponseEntity.ok(markdownContent);
                        } else {
                            log.warn("转换为Markdown失败，返回原始JSON");
                        }
                    } catch (JsonProcessingException e) {
                        log.warn("转换为Markdown失败，返回原始JSON: {}", e.getMessage());
                    }
                }
                
                messagePublisher.publishMessage(CacheEvent.builder().key(cacheKey).rawKey(rawKey).details(responseBody).build());
                return ResponseEntity.ok(responseBody);
            } else {
                return ResponseEntity.status(resp.getStatusCode()).body(resp.body());
            }
        } catch (JsonProcessingException e) {
            return ResponseEntity.status(400).body("Invalid request body");
        } catch (IOException|InterruptedException e) {
            return ResponseEntity.status(500).body("Failed to call Jira API: " + e.getMessage());
        }
    }

    @Data
    public static class SearchRequest {
        private String apiPrefix;
        private String apiVersion;
        private String token;
        private String jql;
    }

    @Data
    public static class Jql {
        private int startAt;
        private int maxResults;
        private String jql;
        private List<String> fields;
    }

    @Data
    public static class JiraProxyRequest {
        private Map<String, String> headers;
        private String url;
    }
}