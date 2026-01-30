package com.phodal.anthropicproxy.model.metrics;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Turn (message) level log - represents one request/response cycle
 * A turn = user sends a message, assistant responds (possibly with tool calls)
 */
@Data
@Builder
public class TurnLog {
    
    private String turnId;
    private String userId;
    private String sessionId;
    private LocalDateTime timestamp;
    private String model;
    private boolean stream;
    private int toolsOfferedCount;  // tools available in the request
    private String lastUserMessage;  // full user's message (may be large)
    private String lastUserMessagePreview;  // truncated preview of user's message
    private int promptTokens;
    private int completionTokens;
    private Long latencyMs;
    private int toolCallCount;
    private int editToolCallCount;
    private int linesModified;  // net change (added - removed)
    private int linesAdded;     // total lines added
    private int linesRemoved;   // total lines removed
    private boolean hasError;
    private String errorMessage;
    
    @Builder.Default
    private List<ToolCallLog> toolCalls = new ArrayList<>();
    
    /**
     * Create a preview of the user message (truncated and sanitized)
     */
    public static String createMessagePreview(String message, int maxLength) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        // Remove potential sensitive content
        String sanitized = message.trim();
        if (sanitized.length() > maxLength) {
            return sanitized.substring(0, maxLength) + "...";
        }
        return sanitized;
    }
    
    public void addToolCall(ToolCallLog toolCall) {
        if (this.toolCalls == null) {
            this.toolCalls = new ArrayList<>();
        }
        this.toolCalls.add(toolCall);
        this.toolCallCount = this.toolCalls.size();
    }
}
