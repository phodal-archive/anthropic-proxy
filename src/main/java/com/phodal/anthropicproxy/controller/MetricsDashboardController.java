package com.phodal.anthropicproxy.controller;

import com.phodal.anthropicproxy.service.MetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

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
}
