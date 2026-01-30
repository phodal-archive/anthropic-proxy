package com.phodal.anthropicproxy.controller;

import com.phodal.anthropicproxy.model.metrics.SessionInfo;
import com.phodal.anthropicproxy.model.metrics.ToolCallLog;
import com.phodal.anthropicproxy.model.metrics.TurnLog;
import com.phodal.anthropicproxy.service.MetricsService;
import com.phodal.anthropicproxy.service.SessionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Controller for metrics dashboard
 */
@Controller
@RequestMapping("/metrics")
@RequiredArgsConstructor
public class MetricsDashboardController {

    private final MetricsService metricsService;

    /**
     * Main dashboard page
     */
    @GetMapping("")
    public String dashboard(Model model) {
        MetricsService.AggregatedMetrics metrics = metricsService.getAggregatedMetrics();
        
        model.addAttribute("totalRequests", metrics.getTotalRequests());
        model.addAttribute("totalToolCalls", metrics.getTotalToolCalls());
        model.addAttribute("totalEditToolCalls", metrics.getTotalEditToolCalls());
        model.addAttribute("totalLinesModified", metrics.getTotalLinesModified());
        model.addAttribute("totalInputTokens", metrics.getTotalInputTokens());
        model.addAttribute("totalOutputTokens", metrics.getTotalOutputTokens());
        model.addAttribute("activeUsers", metrics.getActiveUsers());
        model.addAttribute("toolCallsByName", metrics.getToolCallsByName());
        
        // Get user metrics
        List<Map<String, Object>> userMetricsList = metricsService.getUserMetricsMap().values().stream()
                .map(um -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("userId", um.getUserId());
                    map.put("totalRequests", um.getTotalRequests().get());
                    map.put("totalToolCalls", um.getTotalToolCalls().get());
                    map.put("editToolCalls", um.getEditToolCalls().get());
                    map.put("linesModified", um.getLinesModified().get());
                    map.put("inputTokens", um.getInputTokens().get());
                    map.put("outputTokens", um.getOutputTokens().get());
                    map.put("firstSeen", um.getFirstSeen());
                    map.put("lastSeen", um.getLastSeen());
                    return map;
                })
                .collect(Collectors.toList());
        model.addAttribute("userMetrics", userMetricsList);
        
        // Get recent requests
        model.addAttribute("recentRequests", metricsService.getRecentRequests());
        
        return "dashboard";
    }

    /**
     * JSON API for metrics data
     */
    @GetMapping("/api/summary")
    @ResponseBody
    public MetricsService.AggregatedMetrics getMetricsSummary() {
        return metricsService.getAggregatedMetrics();
    }

    /**
     * JSON API for user metrics
     */
    @GetMapping("/api/users")
    @ResponseBody
    public List<Map<String, Object>> getUserMetrics() {
        return metricsService.getUserMetricsMap().values().stream()
                .map(um -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("userId", um.getUserId());
                    map.put("totalRequests", um.getTotalRequests().get());
                    map.put("totalToolCalls", um.getTotalToolCalls().get());
                    map.put("editToolCalls", um.getEditToolCalls().get());
                    map.put("linesModified", um.getLinesModified().get());
                    map.put("inputTokens", um.getInputTokens().get());
                    map.put("outputTokens", um.getOutputTokens().get());
                    map.put("firstSeen", um.getFirstSeen());
                    map.put("lastSeen", um.getLastSeen());
                    map.put("toolCallsByName", um.getToolCallsByName().entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get())));
                    return map;
                })
                .collect(Collectors.toList());
    }

    /**
     * JSON API for recent requests
     */
    @GetMapping("/api/recent")
    @ResponseBody
    public List<MetricsService.RequestLog> getRecentRequests() {
        return metricsService.getRecentRequests();
    }
    
    /**
     * JSON API for recent turns (message-level detail)
     */
    @GetMapping("/api/turns")
    @ResponseBody
    public List<Map<String, Object>> getRecentTurns() {
        return metricsService.getRecentTurns().stream()
                .map(this::turnLogToMap)
                .collect(Collectors.toList());
    }
    
    /**
     * JSON API for turns by user
     */
    @GetMapping("/api/users/{userId}/turns")
    @ResponseBody
    public List<Map<String, Object>> getTurnsByUser(@PathVariable String userId) {
        return metricsService.getTurnsForUser(userId).stream()
                .map(this::turnLogToMap)
                .collect(Collectors.toList());
    }
    
    /**
     * JSON API for turns by session
     */
    @GetMapping("/api/sessions/{sessionId}/turns")
    @ResponseBody
    public List<Map<String, Object>> getTurnsBySession(@PathVariable String sessionId) {
        return metricsService.getTurnsForSession(sessionId).stream()
                .map(this::turnLogToMap)
                .collect(Collectors.toList());
    }
    
    /**
     * JSON API for a specific turn with tool calls
     */
    @GetMapping("/api/turns/{turnId}")
    @ResponseBody
    public Map<String, Object> getTurnDetail(@PathVariable String turnId) {
        TurnLog turn = metricsService.getTurnById(turnId);
        if (turn == null) {
            return Map.of("error", "Turn not found");
        }
        return turnLogToDetailMap(turn);
    }
    
    /**
     * JSON API for sessions
     */
    @GetMapping("/api/sessions")
    @ResponseBody
    public List<Map<String, Object>> getRecentSessions() {
        SessionManager sessionManager = metricsService.getSessionManager();
        return sessionManager.getRecentSessions(50).stream()
                .map(this::sessionInfoToMap)
                .collect(Collectors.toList());
    }
    
    /**
     * JSON API for sessions by user
     */
    @GetMapping("/api/users/{userId}/sessions")
    @ResponseBody
    public List<Map<String, Object>> getSessionsByUser(@PathVariable String userId) {
        SessionManager sessionManager = metricsService.getSessionManager();
        return sessionManager.getUserSessions(userId).stream()
                .map(this::sessionInfoToMap)
                .collect(Collectors.toList());
    }
    
    private Map<String, Object> turnLogToMap(TurnLog turn) {
        Map<String, Object> map = new HashMap<>();
        map.put("turnId", turn.getTurnId());
        map.put("userId", turn.getUserId());
        map.put("sessionId", turn.getSessionId());
        map.put("timestamp", turn.getTimestamp());
        map.put("model", turn.getModel());
        map.put("stream", turn.isStream());
        map.put("toolsOfferedCount", turn.getToolsOfferedCount());
        map.put("lastUserMessagePreview", turn.getLastUserMessagePreview());
        map.put("promptTokens", turn.getPromptTokens());
        map.put("completionTokens", turn.getCompletionTokens());
        map.put("latencyMs", turn.getLatencyMs());
        map.put("toolCallCount", turn.getToolCallCount());
        map.put("editToolCallCount", turn.getEditToolCallCount());
        map.put("linesModified", turn.getLinesModified());
        map.put("linesAdded", turn.getLinesAdded());
        map.put("linesRemoved", turn.getLinesRemoved());
        map.put("hasError", turn.isHasError());
        map.put("errorMessage", turn.getErrorMessage());
        
        // Include tool calls
        if (turn.getToolCalls() != null) {
            map.put("toolCalls", turn.getToolCalls().stream()
                    .map(this::toolCallLogToMap)
                    .collect(Collectors.toList()));
        }
        
        return map;
    }

    private Map<String, Object> turnLogToDetailMap(TurnLog turn) {
        Map<String, Object> map = turnLogToMap(turn);
        map.put("lastUserMessage", turn.getLastUserMessage());
        return map;
    }
    
    private Map<String, Object> toolCallLogToMap(ToolCallLog toolCall) {
        Map<String, Object> map = new HashMap<>();
        map.put("toolCallId", toolCall.getToolCallId());
        map.put("turnId", toolCall.getTurnId());
        map.put("name", toolCall.getName());
        map.put("argsPreview", toolCall.getArgsPreview());
        map.put("timestamp", toolCall.getTimestamp());
        map.put("durationMs", toolCall.getDurationMs());
        map.put("status", toolCall.getStatus());
        map.put("linesModified", toolCall.getLinesModified());
        map.put("linesAdded", toolCall.getLinesAdded());
        map.put("linesRemoved", toolCall.getLinesRemoved());
        map.put("filePath", toolCall.getFilePath());
        map.put("errorMessage", toolCall.getErrorMessage());
        return map;
    }
    
    private Map<String, Object> sessionInfoToMap(SessionInfo session) {
        Map<String, Object> map = new HashMap<>();
        map.put("sessionId", session.getSessionId());
        map.put("userId", session.getUserId());
        map.put("startTime", session.getStartTime());
        map.put("lastActivityTime", session.getLastActivityTime());
        map.put("turnCount", session.getTurnCount().get());
        map.put("totalToolCalls", session.getTotalToolCalls().get());
        map.put("editToolCalls", session.getEditToolCalls().get());
        map.put("totalLinesModified", session.getTotalLinesModified().get());
        map.put("totalPromptTokens", session.getTotalPromptTokens().get());
        map.put("totalCompletionTokens", session.getTotalCompletionTokens().get());
        map.put("avgToolCallsPerTurn", session.getAvgToolCallsPerTurn());
        map.put("avgLatencyMs", session.getAvgLatencyMs());
        map.put("errorCount", session.getErrorCount().get());
        map.put("toolUsage", session.getToolUsageSnapshot());
        return map;
    }
}
