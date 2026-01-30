package com.phodal.anthropicproxy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.phodal.anthropicproxy.model.anthropic.AnthropicMessage;
import com.phodal.anthropicproxy.model.anthropic.AnthropicRequest;
import com.phodal.anthropicproxy.model.metrics.LinesModifiedInfo;
import com.phodal.anthropicproxy.model.metrics.SessionInfo;
import com.phodal.anthropicproxy.model.metrics.ToolCallLog;
import com.phodal.anthropicproxy.model.metrics.TurnLog;
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
    private final SessionManager sessionManager;

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
    
    // Turn-level logs (message-level detail)
    @Getter
    private final List<TurnLog> recentTurns = Collections.synchronizedList(new LinkedList<>());
    
    // Active turns being processed (turnId -> TurnLog)
    private final Map<String, TurnLog> activeTurns = new ConcurrentHashMap<>();
    
    private static final int MAX_RECENT_REQUESTS = 100;
    private static final int MAX_RECENT_TURNS = 200;
    private static final int MESSAGE_PREVIEW_LENGTH = 100;
    private static final int ARGS_PREVIEW_LENGTH = 150;

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

    public MetricsService(MeterRegistry meterRegistry, ObjectMapper objectMapper, SessionManager sessionManager) {
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
        this.sessionManager = sessionManager;

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
     * Record a request and create a TurnLog
     * Returns turnId for tracking tool calls
     */
    public String recordRequest(String userId, AnthropicRequest request, Map<String, String> headers) {
        totalRequestsCounter.increment();
        
        UserMetrics userMetrics = userMetricsMap.computeIfAbsent(userId, k -> new UserMetrics(userId));
        userMetrics.incrementRequests();
        
        // Get or create session
        SessionInfo session = sessionManager.getOrCreateSession(userId);
        session.incrementTurns();
        
        // Generate turnId
        String turnId = generateTurnId(userId);
        
        // Record with model tag
        Counter.builder("claude_code.requests.by_model")
                .tag("model", request.getModel() != null ? request.getModel() : "unknown")
                .tag("user", userId)
                .register(meterRegistry)
                .increment();

        // Add to recent requests (keep backward compatible)
        RequestLog requestLog = new RequestLog();
        requestLog.setTimestamp(LocalDateTime.now());
        requestLog.setUserId(userId);
        requestLog.setModel(request.getModel());
        requestLog.setHasTools(request.getTools() != null && !request.getTools().isEmpty());
        requestLog.setToolCount(request.getTools() != null ? request.getTools().size() : 0);
        
        addRecentRequest(requestLog);
        
        // Create TurnLog
        String lastUserMessage = extractLastUserMessage(request);
        TurnLog turnLog = TurnLog.builder()
                .turnId(turnId)
                .userId(userId)
                .sessionId(session.getSessionId())
                .timestamp(LocalDateTime.now())
                .model(request.getModel())
                .stream(Boolean.TRUE.equals(request.getStream()))
                .toolsOfferedCount(request.getTools() != null ? request.getTools().size() : 0)
                .lastUserMessage(lastUserMessage != null ? lastUserMessage.trim() : "")
                .lastUserMessagePreview(TurnLog.createMessagePreview(lastUserMessage, MESSAGE_PREVIEW_LENGTH))
                .build();
        
        activeTurns.put(turnId, turnLog);
        
        log.debug("Recorded request from user: {}, model: {}, turnId: {}", userId, request.getModel(), turnId);
        return turnId;
    }
    
    /**
     * Extract the last user message from request for preview
     */
    private String extractLastUserMessage(AnthropicRequest request) {
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return "";
        }
        
        // Find the last user message
        for (int i = request.getMessages().size() - 1; i >= 0; i--) {
            AnthropicMessage msg = request.getMessages().get(i);
            if ("user".equals(msg.getRole())) {
                Object content = msg.getContent();
                if (content instanceof String) {
                    return (String) content;
                } else if (content instanceof List) {
                    // Content blocks - extract text
                    StringBuilder sb = new StringBuilder();
                    try {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> blocks = (List<Map<String, Object>>) content;
                        for (Map<String, Object> block : blocks) {
                            if ("text".equals(block.get("type")) && block.get("text") != null) {
                                sb.append(block.get("text")).append(" ");
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Failed to parse content blocks: {}", e.getMessage());
                    }
                    return sb.toString().trim();
                }
            }
        }
        return "";
    }
    
    private String generateTurnId(String userId) {
        return String.format("turn-%s-%d-%s",
                userId.length() > 6 ? userId.substring(0, 6) : userId,
                System.currentTimeMillis(),
                UUID.randomUUID().toString().substring(0, 6));
    }

    /**
     * Record response and extract tool call metrics
     */
    public void recordResponse(String userId, String turnId, OpenAIResponse response, long latencyMs) {
        TurnLog turnLog = activeTurns.get(turnId);
        
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            if (turnLog != null) {
                finalizeTurnLog(turnLog, latencyMs, true, "Empty response");
            }
            return;
        }

        var message = response.getChoices().get(0).getMessage();
        if (message == null) {
            if (turnLog != null) {
                finalizeTurnLog(turnLog, latencyMs, true, "No message in response");
            }
            return;
        }

        UserMetrics userMetrics = userMetricsMap.computeIfAbsent(userId, k -> new UserMetrics(userId));
        SessionInfo session = sessionManager.getOrCreateSession(userId);

        // Record tool calls
        if (message.getToolCalls() != null) {
            for (OpenAIToolCall toolCall : message.getToolCalls()) {
                recordToolCall(userId, turnId, toolCall, userMetrics, session, turnLog);
            }
        }

        // Record tokens if available
        int promptTokens = 0, completionTokens = 0;
        if (response.getUsage() != null) {
            promptTokens = response.getUsage().getPromptTokens() != null ? response.getUsage().getPromptTokens() : 0;
            completionTokens = response.getUsage().getCompletionTokens() != null ? response.getUsage().getCompletionTokens() : 0;
            userMetrics.addInputTokens(promptTokens);
            userMetrics.addOutputTokens(completionTokens);
            session.addTokens(promptTokens, completionTokens);
        }
        
        if (turnLog != null) {
            turnLog.setPromptTokens(promptTokens);
            turnLog.setCompletionTokens(completionTokens);
            finalizeTurnLog(turnLog, latencyMs, false, null);
        }
    }

    /**
     * Record response from OpenAI SDK ChatCompletion
     */
    public void recordSdkResponse(String userId, String turnId, ChatCompletion completion, long latencyMs) {
        TurnLog turnLog = activeTurns.get(turnId);
        
        if (completion == null || completion.choices().isEmpty()) {
            if (turnLog != null) {
                finalizeTurnLog(turnLog, latencyMs, true, "Empty completion");
            }
            return;
        }

        ChatCompletionMessage message = completion.choices().get(0).message();
        UserMetrics userMetrics = userMetricsMap.computeIfAbsent(userId, k -> new UserMetrics(userId));
        SessionInfo session = sessionManager.getOrCreateSession(userId);

        // Record tool calls - toolCalls() returns Optional<List>
        Optional<List<ChatCompletionMessageToolCall>> toolCallsOpt = message.toolCalls();
        if (toolCallsOpt.isPresent()) {
            List<ChatCompletionMessageToolCall> toolCalls = toolCallsOpt.get();
            for (ChatCompletionMessageToolCall toolCall : toolCalls) {
                recordSdkToolCall(userId, turnId, toolCall, userMetrics, session, turnLog);
            }
        }

        // Record tokens if available
        final int[] tokens = {0, 0};
        completion.usage().ifPresent(usage -> {
            tokens[0] = (int) usage.promptTokens();
            tokens[1] = (int) usage.completionTokens();
            userMetrics.addInputTokens(tokens[0]);
            userMetrics.addOutputTokens(tokens[1]);
            session.addTokens(tokens[0], tokens[1]);
        });
        
        if (turnLog != null) {
            turnLog.setPromptTokens(tokens[0]);
            turnLog.setCompletionTokens(tokens[1]);
            finalizeTurnLog(turnLog, latencyMs, false, null);
        }
    }
    
    /**
     * Finalize a TurnLog and move it to recent turns
     */
    private void finalizeTurnLog(TurnLog turnLog, long latencyMs, boolean hasError, String errorMessage) {
        turnLog.setLatencyMs(latencyMs);
        turnLog.setHasError(hasError);
        turnLog.setErrorMessage(errorMessage);
        
        // Update session latency
        SessionInfo session = sessionManager.getOrCreateSession(turnLog.getUserId());
        session.addLatency(latencyMs);
        if (hasError) {
            session.incrementErrors();
        }
        
        addRecentTurn(turnLog);
        activeTurns.remove(turnLog.getTurnId());
        
        log.debug("Finalized turn {} with {} tool calls, latency {}ms", 
                turnLog.getTurnId(), turnLog.getToolCallCount(), latencyMs);
    }
    
    private void addRecentTurn(TurnLog turnLog) {
        synchronized (recentTurns) {
            recentTurns.add(0, turnLog);
            while (recentTurns.size() > MAX_RECENT_TURNS) {
                recentTurns.remove(recentTurns.size() - 1);
            }
        }
    }

    /**
     * Record a single tool call from SDK
     */
    private void recordSdkToolCall(String userId, String turnId, ChatCompletionMessageToolCall toolCall, 
                                    UserMetrics userMetrics, SessionInfo session, TurnLog turnLog) {
        // Get the function tool call from the union type
        Optional<ChatCompletionMessageFunctionToolCall> funcToolCallOpt = toolCall.function();
        if (funcToolCallOpt.isEmpty()) {
            return;
        }
        
        ChatCompletionMessageFunctionToolCall funcToolCall = funcToolCallOpt.get();
        String toolName = funcToolCall.function().name();
        String toolCallId = funcToolCall.id();
        String args = funcToolCall.function().arguments();
        
        totalToolCallsCounter.increment();
        userMetrics.incrementToolCalls();
        userMetrics.addToolCall(toolName);
        session.addToolCall(toolName);

        // Record by tool name
        Counter.builder("claude_code.tool_calls.by_name")
                .tag("tool", toolName)
                .tag("user", userId)
                .register(meterRegistry)
                .increment();

        LinesModifiedInfo linesInfo = LinesModifiedInfo.empty();
        boolean isEdit = isEditTool(toolName);
        
        // Check if it's an edit tool
        if (isEdit) {
            editToolCallsCounter.increment();
            userMetrics.incrementEditToolCalls();
            session.addEditToolCall();
            
            // Extract lines modified from the arguments
            linesInfo = extractLinesModifiedFromSdkToolCall(funcToolCall);
            int netChange = linesInfo.getAbsoluteChange();
            if (netChange > 0) {
                totalLinesModifiedCounter.increment(netChange);
                userMetrics.addLinesModified(netChange);
                session.addLinesModified(netChange);
            }
        }
        
        // Create ToolCallLog and add to TurnLog
        if (turnLog != null) {
            ToolCallLog toolCallLog = ToolCallLog.builder()
                    .toolCallId(toolCallId != null ? toolCallId : UUID.randomUUID().toString())
                    .turnId(turnId)
                    .name(toolName)
                    .argsPreview(ToolCallLog.createArgsPreview(args, ARGS_PREVIEW_LENGTH))
                    .timestamp(LocalDateTime.now())
                    .status("ok")
                    .linesModified(linesInfo.getNetChange())
                    .linesAdded(linesInfo.getLinesAdded())
                    .linesRemoved(linesInfo.getLinesRemoved())
                    .filePath(linesInfo.getFilePath())
                    .build();
            turnLog.addToolCall(toolCallLog);
            if (isEdit) {
                turnLog.setEditToolCallCount(turnLog.getEditToolCallCount() + 1);
                turnLog.setLinesModified(turnLog.getLinesModified() + linesInfo.getNetChange());
                turnLog.setLinesAdded(turnLog.getLinesAdded() + linesInfo.getLinesAdded());
                turnLog.setLinesRemoved(turnLog.getLinesRemoved() + linesInfo.getLinesRemoved());
            }
        }

        log.debug("Recorded SDK tool call: {} for user: {}, turnId: {}, +{} -{} lines", 
                toolName, userId, turnId, linesInfo.getLinesAdded(), linesInfo.getLinesRemoved());
    }

    /**
     * Extract number of lines modified from SDK tool call arguments
     */
    private LinesModifiedInfo extractLinesModifiedFromSdkToolCall(ChatCompletionMessageFunctionToolCall funcToolCall) {
        try {
            String args = funcToolCall.function().arguments();
            return extractLinesModifiedFromArgs(args);
        } catch (Exception e) {
            log.debug("Failed to extract lines modified from SDK: {}", e.getMessage());
            return LinesModifiedInfo.empty();
        }
    }

    /**
     * Record streaming complete with collected tool calls (using ToolCallInfo from OpenAISdkService)
     */
    public void recordStreamingToolCalls(String userId, String turnId, List<OpenAISdkService.ToolCallInfo> toolCalls, long latencyMs) {
        TurnLog turnLog = activeTurns.get(turnId);
        
        if (toolCalls == null || toolCalls.isEmpty()) {
            if (turnLog != null) {
                finalizeTurnLog(turnLog, latencyMs, false, null);
            }
            return;
        }

        UserMetrics userMetrics = userMetricsMap.computeIfAbsent(userId, k -> new UserMetrics(userId));
        SessionInfo session = sessionManager.getOrCreateSession(userId);
        
        for (OpenAISdkService.ToolCallInfo toolCallInfo : toolCalls) {
            recordToolCallInfo(userId, turnId, toolCallInfo, userMetrics, session, turnLog);
        }
        
        if (turnLog != null) {
            finalizeTurnLog(turnLog, latencyMs, false, null);
        }
    }
    
    /**
     * Record a tool call from ToolCallInfo
     */
    private void recordToolCallInfo(String userId, String turnId, OpenAISdkService.ToolCallInfo toolCallInfo, 
                                     UserMetrics userMetrics, SessionInfo session, TurnLog turnLog) {
        String toolName = toolCallInfo.name();
        if (toolName == null) {
            toolName = "unknown";
        }
        String toolCallId = toolCallInfo.id();
        String args = toolCallInfo.arguments();
        
        totalToolCallsCounter.increment();
        userMetrics.incrementToolCalls();
        userMetrics.addToolCall(toolName);
        session.addToolCall(toolName);

        // Record by tool name
        Counter.builder("claude_code.tool_calls.by_name")
                .tag("tool", toolName)
                .tag("user", userId)
                .register(meterRegistry)
                .increment();

        LinesModifiedInfo linesInfo = LinesModifiedInfo.empty();
        boolean isEdit = isEditTool(toolName);
        
        // Check if it's an edit tool
        if (isEdit) {
            editToolCallsCounter.increment();
            userMetrics.incrementEditToolCalls();
            session.addEditToolCall();
            
            // Extract lines modified from the arguments
            linesInfo = extractLinesModifiedFromArgs(args);
            int netChange = linesInfo.getAbsoluteChange(); // Use absolute for counters
            if (netChange > 0) {
                totalLinesModifiedCounter.increment(netChange);
                userMetrics.addLinesModified(netChange);
                session.addLinesModified(netChange);
            }
        }
        
        // Create ToolCallLog and add to TurnLog
        if (turnLog != null) {
            ToolCallLog toolCallLog = ToolCallLog.builder()
                    .toolCallId(toolCallId != null ? toolCallId : UUID.randomUUID().toString())
                    .turnId(turnId)
                    .name(toolName)
                    .argsPreview(ToolCallLog.createArgsPreview(args, ARGS_PREVIEW_LENGTH))
                    .timestamp(LocalDateTime.now())
                    .status("ok")
                    .linesModified(linesInfo.getNetChange())
                    .linesAdded(linesInfo.getLinesAdded())
                    .linesRemoved(linesInfo.getLinesRemoved())
                    .filePath(linesInfo.getFilePath())
                    .build();
            turnLog.addToolCall(toolCallLog);
            if (isEdit) {
                turnLog.setEditToolCallCount(turnLog.getEditToolCallCount() + 1);
                turnLog.setLinesModified(turnLog.getLinesModified() + linesInfo.getNetChange());
                turnLog.setLinesAdded(turnLog.getLinesAdded() + linesInfo.getLinesAdded());
                turnLog.setLinesRemoved(turnLog.getLinesRemoved() + linesInfo.getLinesRemoved());
            }
        }

        log.debug("Recorded streaming tool call: {} for user: {}, turnId: {}, +{} -{} lines", 
                toolName, userId, turnId, linesInfo.getLinesAdded(), linesInfo.getLinesRemoved());
    }
    
    /**
     * Extract lines modified from arguments string
     * Returns detailed info including added/removed lines and file path
     */
    private LinesModifiedInfo extractLinesModifiedFromArgs(String args) {
        if (args == null || args.isEmpty()) {
            return LinesModifiedInfo.empty();
        }
        
        try {
            Map<String, Object> argsMap = objectMapper.readValue(args, Map.class);
            
            // Extract file path if available
            String filePath = null;
            if (argsMap.containsKey("file_path")) {
                filePath = String.valueOf(argsMap.get("file_path"));
            } else if (argsMap.containsKey("path")) {
                filePath = String.valueOf(argsMap.get("path"));
            } else if (argsMap.containsKey("filePath")) {
                filePath = String.valueOf(argsMap.get("filePath"));
            }

            int newLines = 0;
            int oldLines = 0;

            // Check for replacement patterns (has both old and new)
            // Pattern 1: old_string / new_string (StrReplace style)
            if (argsMap.containsKey("old_string") && argsMap.containsKey("new_string")) {
                String oldStr = String.valueOf(argsMap.get("old_string"));
                String newStr = String.valueOf(argsMap.get("new_string"));
                oldLines = countLines(oldStr);
                newLines = countLines(newStr);
                return new LinesModifiedInfo(newLines, oldLines, filePath);
            }
            
            // Pattern 2: old_str / new_str (Anthropic style)
            if (argsMap.containsKey("old_str") && argsMap.containsKey("new_str")) {
                String oldStr = String.valueOf(argsMap.get("old_str"));
                String newStr = String.valueOf(argsMap.get("new_str"));
                oldLines = countLines(oldStr);
                newLines = countLines(newStr);
                return new LinesModifiedInfo(newLines, oldLines, filePath);
            }
            
            // Pattern 3: oldString / newString (camelCase style)
            if (argsMap.containsKey("oldString") && argsMap.containsKey("newString")) {
                String oldStr = String.valueOf(argsMap.get("oldString"));
                String newStr = String.valueOf(argsMap.get("newString"));
                oldLines = countLines(oldStr);
                newLines = countLines(newStr);
                return new LinesModifiedInfo(newLines, oldLines, filePath);
            }

            // For write/create operations (only new content)
            if (argsMap.containsKey("new_str")) {
                newLines = countLines(String.valueOf(argsMap.get("new_str")));
            } else if (argsMap.containsKey("content")) {
                newLines = countLines(String.valueOf(argsMap.get("content")));
            } else if (argsMap.containsKey("contents")) {
                newLines = countLines(String.valueOf(argsMap.get("contents")));
            } else if (argsMap.containsKey("newString")) {
                newLines = countLines(String.valueOf(argsMap.get("newString")));
            } else if (argsMap.containsKey("code")) {
                newLines = countLines(String.valueOf(argsMap.get("code")));
            } else if (argsMap.containsKey("newCode")) {
                newLines = countLines(String.valueOf(argsMap.get("newCode")));
            }

            return new LinesModifiedInfo(newLines, oldLines, filePath);
        } catch (Exception e) {
            log.debug("Failed to extract lines modified from args: {}", e.getMessage());
            return LinesModifiedInfo.empty();
        }
    }

    /**
     * Record a single tool call
     */
    private void recordToolCall(String userId, String turnId, OpenAIToolCall toolCall, 
                                 UserMetrics userMetrics, SessionInfo session, TurnLog turnLog) {
        String toolName = toolCall.getFunction() != null ? toolCall.getFunction().getName() : "unknown";
        String toolCallId = toolCall.getId();
        String args = toolCall.getFunction() != null ? toolCall.getFunction().getArguments() : null;
        
        totalToolCallsCounter.increment();
        userMetrics.incrementToolCalls();
        userMetrics.addToolCall(toolName);
        session.addToolCall(toolName);

        // Record by tool name
        Counter.builder("claude_code.tool_calls.by_name")
                .tag("tool", toolName)
                .tag("user", userId)
                .register(meterRegistry)
                .increment();

        LinesModifiedInfo linesInfo = LinesModifiedInfo.empty();
        boolean isEdit = isEditTool(toolName);
        
        // Check if it's an edit tool
        if (isEdit) {
            editToolCallsCounter.increment();
            userMetrics.incrementEditToolCalls();
            session.addEditToolCall();
            
            // Extract lines modified from the arguments
            linesInfo = extractLinesModified(toolCall);
            int netChange = linesInfo.getAbsoluteChange();
            if (netChange > 0) {
                totalLinesModifiedCounter.increment(netChange);
                userMetrics.addLinesModified(netChange);
                session.addLinesModified(netChange);
            }
        }
        
        // Create ToolCallLog and add to TurnLog
        if (turnLog != null) {
            ToolCallLog toolCallLog = ToolCallLog.builder()
                    .toolCallId(toolCallId != null ? toolCallId : UUID.randomUUID().toString())
                    .turnId(turnId)
                    .name(toolName)
                    .argsPreview(ToolCallLog.createArgsPreview(args, ARGS_PREVIEW_LENGTH))
                    .timestamp(LocalDateTime.now())
                    .status("ok")
                    .linesModified(linesInfo.getNetChange())
                    .linesAdded(linesInfo.getLinesAdded())
                    .linesRemoved(linesInfo.getLinesRemoved())
                    .filePath(linesInfo.getFilePath())
                    .build();
            turnLog.addToolCall(toolCallLog);
            if (isEdit) {
                turnLog.setEditToolCallCount(turnLog.getEditToolCallCount() + 1);
                turnLog.setLinesModified(turnLog.getLinesModified() + linesInfo.getNetChange());
                turnLog.setLinesAdded(turnLog.getLinesAdded() + linesInfo.getLinesAdded());
                turnLog.setLinesRemoved(turnLog.getLinesRemoved() + linesInfo.getLinesRemoved());
            }
        }

        log.debug("Recorded tool call: {} for user: {}, turnId: {}, +{} -{} lines", 
                toolName, userId, turnId, linesInfo.getLinesAdded(), linesInfo.getLinesRemoved());
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
    private LinesModifiedInfo extractLinesModified(OpenAIToolCall toolCall) {
        if (toolCall.getFunction() == null || toolCall.getFunction().getArguments() == null) {
            return LinesModifiedInfo.empty();
        }
        return extractLinesModifiedFromArgs(toolCall.getFunction().getArguments());
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
     * Get session manager for accessing session data
     */
    public SessionManager getSessionManager() {
        return sessionManager;
    }
    
    /**
     * Get turns for a specific user
     */
    public List<TurnLog> getTurnsForUser(String userId) {
        return recentTurns.stream()
                .filter(t -> userId.equals(t.getUserId()))
                .toList();
    }
    
    /**
     * Get turns for a specific session
     */
    public List<TurnLog> getTurnsForSession(String sessionId) {
        return recentTurns.stream()
                .filter(t -> sessionId.equals(t.getSessionId()))
                .toList();
    }
    
    /**
     * Get a specific turn by ID
     */
    public TurnLog getTurnById(String turnId) {
        // Check active turns first
        TurnLog active = activeTurns.get(turnId);
        if (active != null) {
            return active;
        }
        // Check recent turns
        return recentTurns.stream()
                .filter(t -> turnId.equals(t.getTurnId()))
                .findFirst()
                .orElse(null);
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
