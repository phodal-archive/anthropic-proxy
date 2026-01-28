package com.phodal.anthropicproxy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.phodal.anthropicproxy.model.anthropic.AnthropicRequest;
import com.phodal.anthropicproxy.model.openai.OpenAIResponse;
import com.phodal.anthropicproxy.model.openai.OpenAIToolCall;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service to collect and manage metrics for Claude Code usage
 */
@Slf4j
@Service
public class MetricsService {

    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    // Counters
    private final Counter totalRequestsCounter;
    private final Counter totalToolCallsCounter;
    private final Counter editToolCallsCounter;
    private final Counter totalLinesModifiedCounter;

    // In-memory storage for detailed metrics
    @Getter
    private final Map<String, UserMetrics> userMetricsMap = new ConcurrentHashMap<>();
    
    @Getter
    private final List<RequestLog> recentRequests = Collections.synchronizedList(new LinkedList<>());
    
    private static final int MAX_RECENT_REQUESTS = 100;

    // Tool names that are considered edit tools
    private static final Set<String> EDIT_TOOL_NAMES = Set.of(
            "str_replace_editor",
            "edit_file",
            "replace_string_in_file",
            "multi_replace_string_in_file",
            "create_file",
            "write_file",
            "insert_code",
            "delete_file",
            "edit_notebook_file"
    );

    public MetricsService(MeterRegistry meterRegistry, ObjectMapper objectMapper) {
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;

        this.totalRequestsCounter = Counter.builder("claude_code.requests.total")
                .description("Total number of requests")
                .register(meterRegistry);

        this.totalToolCallsCounter = Counter.builder("claude_code.tool_calls.total")
                .description("Total number of tool calls")
                .register(meterRegistry);

        this.editToolCallsCounter = Counter.builder("claude_code.edit_tool_calls.total")
                .description("Total number of edit tool calls")
                .register(meterRegistry);

        this.totalLinesModifiedCounter = Counter.builder("claude_code.lines_modified.total")
                .description("Total number of lines modified")
                .register(meterRegistry);
    }

    /**
     * Record a request
     */
    public void recordRequest(String userId, AnthropicRequest request, Map<String, String> headers) {
        totalRequestsCounter.increment();
        
        UserMetrics userMetrics = userMetricsMap.computeIfAbsent(userId, k -> new UserMetrics(userId));
        userMetrics.incrementRequests();
        
        // Record with model tag
        Counter.builder("claude_code.requests.by_model")
                .tag("model", request.getModel() != null ? request.getModel() : "unknown")
                .tag("user", userId)
                .register(meterRegistry)
                .increment();

        // Add to recent requests
        RequestLog requestLog = new RequestLog();
        requestLog.setTimestamp(LocalDateTime.now());
        requestLog.setUserId(userId);
        requestLog.setModel(request.getModel());
        requestLog.setHasTools(request.getTools() != null && !request.getTools().isEmpty());
        requestLog.setToolCount(request.getTools() != null ? request.getTools().size() : 0);
        
        addRecentRequest(requestLog);
        
        log.debug("Recorded request from user: {}, model: {}", userId, request.getModel());
    }

    /**
     * Record response and extract tool call metrics
     */
    public void recordResponse(String userId, OpenAIResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            return;
        }

        var message = response.getChoices().get(0).getMessage();
        if (message == null) {
            return;
        }

        UserMetrics userMetrics = userMetricsMap.computeIfAbsent(userId, k -> new UserMetrics(userId));

        // Record tool calls
        if (message.getToolCalls() != null) {
            for (OpenAIToolCall toolCall : message.getToolCalls()) {
                recordToolCall(userId, toolCall, userMetrics);
            }
        }

        // Record tokens if available
        if (response.getUsage() != null) {
            userMetrics.addInputTokens(response.getUsage().getPromptTokens() != null ? response.getUsage().getPromptTokens() : 0);
            userMetrics.addOutputTokens(response.getUsage().getCompletionTokens() != null ? response.getUsage().getCompletionTokens() : 0);
        }
    }

    /**
     * Record response from OpenAI SDK ChatCompletion
     */
    public void recordSdkResponse(String userId, ChatCompletion completion) {
        if (completion == null || completion.choices().isEmpty()) {
            return;
        }

        ChatCompletionMessage message = completion.choices().get(0).message();
        UserMetrics userMetrics = userMetricsMap.computeIfAbsent(userId, k -> new UserMetrics(userId));

        // Record tool calls - toolCalls() returns Optional<List>
        Optional<List<ChatCompletionMessageToolCall>> toolCallsOpt = message.toolCalls();
        if (toolCallsOpt.isPresent()) {
            List<ChatCompletionMessageToolCall> toolCalls = toolCallsOpt.get();
            for (ChatCompletionMessageToolCall toolCall : toolCalls) {
                recordSdkToolCall(userId, toolCall, userMetrics);
            }
        }

        // Record tokens if available
        completion.usage().ifPresent(usage -> {
            userMetrics.addInputTokens((int) usage.promptTokens());
            userMetrics.addOutputTokens((int) usage.completionTokens());
        });
    }

    /**
     * Record a single tool call from SDK
     */
    private void recordSdkToolCall(String userId, ChatCompletionMessageToolCall toolCall, UserMetrics userMetrics) {
        // Get the function tool call from the union type
        Optional<ChatCompletionMessageFunctionToolCall> funcToolCallOpt = toolCall.function();
        if (funcToolCallOpt.isEmpty()) {
            return;
        }
        
        ChatCompletionMessageFunctionToolCall funcToolCall = funcToolCallOpt.get();
        String toolName = funcToolCall.function().name();
        
        totalToolCallsCounter.increment();
        userMetrics.incrementToolCalls();
        userMetrics.addToolCall(toolName);

        // Record by tool name
        Counter.builder("claude_code.tool_calls.by_name")
                .tag("tool", toolName)
                .tag("user", userId)
                .register(meterRegistry)
                .increment();

        // Check if it's an edit tool
        if (isEditTool(toolName)) {
            editToolCallsCounter.increment();
            userMetrics.incrementEditToolCalls();
            
            // Try to extract lines modified from the arguments
            int linesModified = extractLinesModifiedFromSdkToolCall(funcToolCall);
            if (linesModified > 0) {
                totalLinesModifiedCounter.increment(linesModified);
                userMetrics.addLinesModified(linesModified);
            }
        }

        log.debug("Recorded SDK tool call: {} for user: {}", toolName, userId);
    }

    /**
     * Extract number of lines modified from SDK tool call arguments
     */
    private int extractLinesModifiedFromSdkToolCall(ChatCompletionMessageFunctionToolCall funcToolCall) {
        try {
            String args = funcToolCall.function().arguments();
            Map<String, Object> argsMap = objectMapper.readValue(args, Map.class);

            int lines = 0;

            if (argsMap.containsKey("new_str")) {
                String newStr = String.valueOf(argsMap.get("new_str"));
                lines = countLines(newStr);
            } else if (argsMap.containsKey("content")) {
                String content = String.valueOf(argsMap.get("content"));
                lines = countLines(content);
            } else if (argsMap.containsKey("newString")) {
                String newString = String.valueOf(argsMap.get("newString"));
                lines = countLines(newString);
            } else if (argsMap.containsKey("code")) {
                String code = String.valueOf(argsMap.get("code"));
                lines = countLines(code);
            } else if (argsMap.containsKey("newCode")) {
                String newCode = String.valueOf(argsMap.get("newCode"));
                lines = countLines(newCode);
            }

            return lines;
        } catch (Exception e) {
            log.debug("Failed to extract lines modified from SDK: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Record streaming complete with collected tool calls (using ToolCallInfo from OpenAISdkService)
     */
    public void recordStreamingToolCalls(String userId, List<OpenAISdkService.ToolCallInfo> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }

        UserMetrics userMetrics = userMetricsMap.computeIfAbsent(userId, k -> new UserMetrics(userId));
        
        for (OpenAISdkService.ToolCallInfo toolCallInfo : toolCalls) {
            recordToolCallInfo(userId, toolCallInfo, userMetrics);
        }
    }
    
    /**
     * Record a tool call from ToolCallInfo
     */
    private void recordToolCallInfo(String userId, OpenAISdkService.ToolCallInfo toolCallInfo, UserMetrics userMetrics) {
        String toolName = toolCallInfo.name();
        if (toolName == null) {
            toolName = "unknown";
        }
        
        totalToolCallsCounter.increment();
        userMetrics.incrementToolCalls();
        userMetrics.addToolCall(toolName);

        // Record by tool name
        Counter.builder("claude_code.tool_calls.by_name")
                .tag("tool", toolName)
                .tag("user", userId)
                .register(meterRegistry)
                .increment();

        // Check if it's an edit tool
        if (isEditTool(toolName)) {
            editToolCallsCounter.increment();
            userMetrics.incrementEditToolCalls();
            
            // Try to extract lines modified from the arguments
            int linesModified = extractLinesModifiedFromArgs(toolCallInfo.arguments());
            if (linesModified > 0) {
                totalLinesModifiedCounter.increment(linesModified);
                userMetrics.addLinesModified(linesModified);
            }
        }

        log.debug("Recorded streaming tool call: {} for user: {}", toolName, userId);
    }
    
    /**
     * Extract lines modified from arguments string
     */
    private int extractLinesModifiedFromArgs(String args) {
        if (args == null || args.isEmpty()) {
            return 0;
        }
        
        try {
            Map<String, Object> argsMap = objectMapper.readValue(args, Map.class);

            int lines = 0;

            if (argsMap.containsKey("new_str")) {
                String newStr = String.valueOf(argsMap.get("new_str"));
                lines = countLines(newStr);
            } else if (argsMap.containsKey("content")) {
                String content = String.valueOf(argsMap.get("content"));
                lines = countLines(content);
            } else if (argsMap.containsKey("newString")) {
                String newString = String.valueOf(argsMap.get("newString"));
                lines = countLines(newString);
            } else if (argsMap.containsKey("code")) {
                String code = String.valueOf(argsMap.get("code"));
                lines = countLines(code);
            } else if (argsMap.containsKey("newCode")) {
                String newCode = String.valueOf(argsMap.get("newCode"));
                lines = countLines(newCode);
            }

            return lines;
        } catch (Exception e) {
            log.debug("Failed to extract lines modified from args: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Record a single tool call
     */
    private void recordToolCall(String userId, OpenAIToolCall toolCall, UserMetrics userMetrics) {
        String toolName = toolCall.getFunction() != null ? toolCall.getFunction().getName() : "unknown";
        
        totalToolCallsCounter.increment();
        userMetrics.incrementToolCalls();
        userMetrics.addToolCall(toolName);

        // Record by tool name
        Counter.builder("claude_code.tool_calls.by_name")
                .tag("tool", toolName)
                .tag("user", userId)
                .register(meterRegistry)
                .increment();

        // Check if it's an edit tool
        if (isEditTool(toolName)) {
            editToolCallsCounter.increment();
            userMetrics.incrementEditToolCalls();
            
            // Try to extract lines modified from the arguments
            int linesModified = extractLinesModified(toolCall);
            if (linesModified > 0) {
                totalLinesModifiedCounter.increment(linesModified);
                userMetrics.addLinesModified(linesModified);
            }
        }

        log.debug("Recorded tool call: {} for user: {}", toolName, userId);
    }

    /**
     * Check if the tool is an edit tool
     */
    private boolean isEditTool(String toolName) {
        if (toolName == null) return false;
        String lowerName = toolName.toLowerCase();
        return EDIT_TOOL_NAMES.stream().anyMatch(editTool -> 
                lowerName.contains(editTool.toLowerCase()) || 
                lowerName.contains("edit") || 
                lowerName.contains("write") ||
                lowerName.contains("replace") ||
                lowerName.contains("create_file") ||
                lowerName.contains("modify"));
    }

    /**
     * Extract number of lines modified from tool call arguments
     */
    private int extractLinesModified(OpenAIToolCall toolCall) {
        if (toolCall.getFunction() == null || toolCall.getFunction().getArguments() == null) {
            return 0;
        }

        try {
            String args = toolCall.getFunction().getArguments();
            Map<String, Object> argsMap = objectMapper.readValue(args, Map.class);

            // Try different argument patterns
            int lines = 0;

            // For str_replace_editor or similar tools
            if (argsMap.containsKey("new_str")) {
                String newStr = String.valueOf(argsMap.get("new_str"));
                lines = countLines(newStr);
            } else if (argsMap.containsKey("content")) {
                String content = String.valueOf(argsMap.get("content"));
                lines = countLines(content);
            } else if (argsMap.containsKey("newString")) {
                String newString = String.valueOf(argsMap.get("newString"));
                lines = countLines(newString);
            } else if (argsMap.containsKey("code")) {
                String code = String.valueOf(argsMap.get("code"));
                lines = countLines(code);
            } else if (argsMap.containsKey("newCode")) {
                String newCode = String.valueOf(argsMap.get("newCode"));
                lines = countLines(newCode);
            }

            return lines;
        } catch (Exception e) {
            log.debug("Failed to extract lines modified: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Count lines in a string
     */
    private int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.split("\n").length;
    }

    /**
     * Add a request to recent requests list
     */
    private void addRecentRequest(RequestLog requestLog) {
        synchronized (recentRequests) {
            recentRequests.add(0, requestLog);
            while (recentRequests.size() > MAX_RECENT_REQUESTS) {
                recentRequests.remove(recentRequests.size() - 1);
            }
        }
    }

    /**
     * Get aggregated metrics
     */
    public AggregatedMetrics getAggregatedMetrics() {
        AggregatedMetrics metrics = new AggregatedMetrics();
        
        long totalRequests = 0;
        long totalToolCalls = 0;
        long totalEditToolCalls = 0;
        long totalLinesModified = 0;
        long totalInputTokens = 0;
        long totalOutputTokens = 0;
        Map<String, Long> toolCallsByName = new HashMap<>();

        for (UserMetrics userMetrics : userMetricsMap.values()) {
            totalRequests += userMetrics.getTotalRequests().get();
            totalToolCalls += userMetrics.getTotalToolCalls().get();
            totalEditToolCalls += userMetrics.getEditToolCalls().get();
            totalLinesModified += userMetrics.getLinesModified().get();
            totalInputTokens += userMetrics.getInputTokens().get();
            totalOutputTokens += userMetrics.getOutputTokens().get();
            
            for (Map.Entry<String, AtomicLong> entry : userMetrics.getToolCallsByName().entrySet()) {
                toolCallsByName.merge(entry.getKey(), entry.getValue().get(), Long::sum);
            }
        }

        metrics.setTotalRequests(totalRequests);
        metrics.setTotalToolCalls(totalToolCalls);
        metrics.setTotalEditToolCalls(totalEditToolCalls);
        metrics.setTotalLinesModified(totalLinesModified);
        metrics.setTotalInputTokens(totalInputTokens);
        metrics.setTotalOutputTokens(totalOutputTokens);
        metrics.setActiveUsers(userMetricsMap.size());
        metrics.setToolCallsByName(toolCallsByName);

        return metrics;
    }

    /**
     * User-specific metrics
     */
    @Getter
    public static class UserMetrics {
        private final String userId;
        private final AtomicLong totalRequests = new AtomicLong(0);
        private final AtomicLong totalToolCalls = new AtomicLong(0);
        private final AtomicLong editToolCalls = new AtomicLong(0);
        private final AtomicLong linesModified = new AtomicLong(0);
        private final AtomicLong inputTokens = new AtomicLong(0);
        private final AtomicLong outputTokens = new AtomicLong(0);
        private final Map<String, AtomicLong> toolCallsByName = new ConcurrentHashMap<>();
        private LocalDateTime firstSeen;
        private LocalDateTime lastSeen;

        public UserMetrics(String userId) {
            this.userId = userId;
            this.firstSeen = LocalDateTime.now();
            this.lastSeen = LocalDateTime.now();
        }

        public void incrementRequests() {
            totalRequests.incrementAndGet();
            lastSeen = LocalDateTime.now();
        }

        public void incrementToolCalls() {
            totalToolCalls.incrementAndGet();
        }

        public void incrementEditToolCalls() {
            editToolCalls.incrementAndGet();
        }

        public void addLinesModified(int lines) {
            linesModified.addAndGet(lines);
        }

        public void addInputTokens(int tokens) {
            inputTokens.addAndGet(tokens);
        }

        public void addOutputTokens(int tokens) {
            outputTokens.addAndGet(tokens);
        }

        public void addToolCall(String toolName) {
            toolCallsByName.computeIfAbsent(toolName, k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    /**
     * Request log entry
     */
    @Getter
    public static class RequestLog {
        private LocalDateTime timestamp;
        private String userId;
        private String model;
        private boolean hasTools;
        private int toolCount;
        private List<String> toolsUsed;

        public void setTimestamp(LocalDateTime timestamp) {
            this.timestamp = timestamp;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public void setHasTools(boolean hasTools) {
            this.hasTools = hasTools;
        }

        public void setToolCount(int toolCount) {
            this.toolCount = toolCount;
        }

        public void setToolsUsed(List<String> toolsUsed) {
            this.toolsUsed = toolsUsed;
        }
    }

    /**
     * Aggregated metrics
     */
    @Getter
    public static class AggregatedMetrics {
        private long totalRequests;
        private long totalToolCalls;
        private long totalEditToolCalls;
        private long totalLinesModified;
        private long totalInputTokens;
        private long totalOutputTokens;
        private int activeUsers;
        private Map<String, Long> toolCallsByName;

        public void setTotalRequests(long totalRequests) {
            this.totalRequests = totalRequests;
        }

        public void setTotalToolCalls(long totalToolCalls) {
            this.totalToolCalls = totalToolCalls;
        }

        public void setTotalEditToolCalls(long totalEditToolCalls) {
            this.totalEditToolCalls = totalEditToolCalls;
        }

        public void setTotalLinesModified(long totalLinesModified) {
            this.totalLinesModified = totalLinesModified;
        }

        public void setTotalInputTokens(long totalInputTokens) {
            this.totalInputTokens = totalInputTokens;
        }

        public void setTotalOutputTokens(long totalOutputTokens) {
            this.totalOutputTokens = totalOutputTokens;
        }

        public void setActiveUsers(int activeUsers) {
            this.activeUsers = activeUsers;
        }

        public void setToolCallsByName(Map<String, Long> toolCallsByName) {
            this.toolCallsByName = toolCallsByName;
        }
    }
}
