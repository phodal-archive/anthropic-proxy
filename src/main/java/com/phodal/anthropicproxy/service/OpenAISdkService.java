package com.phodal.anthropicproxy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.*;
import com.openai.models.completions.CompletionUsage;
import com.phodal.anthropicproxy.model.anthropic.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service using official OpenAI Java SDK for API calls
 */
@Slf4j
@Service
public class OpenAISdkService {

    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final MetricsService metricsService;

    public OpenAISdkService(
            @Value("${proxy.openai.base-url}") String baseUrl,
            ObjectMapper objectMapper,
            MetricsService metricsService) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
    }

    /**
     * Create OpenAI client with specific API key
     */
    private OpenAIClient createClient(String apiKey) {
        log.debug("Creating OpenAI client with baseUrl: {}, apiKey: {}", baseUrl, apiKey.substring(0, Math.min(15, apiKey.length())) + "...");
        return OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
    }

    /**
     * Send non-streaming request using OpenAI SDK
     */
    public Mono<AnthropicResponse> sendRequest(AnthropicRequest anthropicRequest, String userId, String apiKey) {
        return Mono.fromCallable(() -> {
            OpenAIClient client = createClient(apiKey);
            ChatCompletionCreateParams params = buildChatCompletionParams(anthropicRequest);
            
            log.debug("Sending non-streaming request to OpenAI API for user: {}", userId);
            
            ChatCompletion completion = client.chat().completions().create(params);
            
            // Record metrics
            metricsService.recordSdkResponse(userId, completion);
            
            return convertToAnthropicResponse(completion, anthropicRequest.getModel());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Send streaming request using OpenAI SDK
     */
    public Flux<String> sendStreamingRequest(AnthropicRequest anthropicRequest, String userId, String apiKey) {
        return Flux.<String>create(sink -> {
            try {
                log.debug("Creating client for streaming request, userId: {}, apiKey: {}", userId, apiKey.substring(0, Math.min(15, apiKey.length())) + "...");
                OpenAIClient client = createClient(apiKey);
                ChatCompletionCreateParams params = buildChatCompletionParams(anthropicRequest);
                
                String requestModel = anthropicRequest.getModel();
                String messageId = "msg_" + UUID.randomUUID().toString().replace("-", "");
                
                log.debug("Sending streaming request to OpenAI API for user: {}", userId);

                // State tracking for stream conversion
                AtomicReference<StringBuilder> currentToolArgs = new AtomicReference<>(new StringBuilder());
                AtomicReference<String> currentToolName = new AtomicReference<>();
                AtomicReference<String> currentToolId = new AtomicReference<>();
                AtomicInteger currentToolIndex = new AtomicInteger(-1);
                AtomicInteger contentBlockIndex = new AtomicInteger(0);
                AtomicReference<Boolean> messageStartSent = new AtomicReference<>(false);
                AtomicReference<Boolean> textBlockStarted = new AtomicReference<>(false);
                AtomicReference<List<ToolCallInfo>> collectedToolCalls = new AtomicReference<>(new ArrayList<>());

                try (StreamResponse<ChatCompletionChunk> streamResponse = 
                        client.chat().completions().createStreaming(params)) {
                    
                    streamResponse.stream().forEach(chunk -> {
                        List<String> events = new ArrayList<>();

                        // Send message_start on first chunk
                        if (!messageStartSent.getAndSet(true)) {
                            Map<String, Object> messageData = new HashMap<>();
                            messageData.put("id", messageId);
                            messageData.put("type", "message");
                            messageData.put("role", "assistant");
                            messageData.put("content", List.of());
                            messageData.put("model", requestModel != null ? requestModel : "unknown");
                            messageData.put("stop_reason", null);
                            messageData.put("stop_sequence", null);
                            messageData.put("usage", Map.of("input_tokens", 0, "output_tokens", 0));
                            
                            Map<String, Object> startEvent = new HashMap<>();
                            startEvent.put("type", "message_start");
                            startEvent.put("message", messageData);
                            events.add(formatSSE(startEvent));
                        }

                        List<ChatCompletionChunk.Choice> choices = chunk.choices();
                        if (choices != null && !choices.isEmpty()) {
                            ChatCompletionChunk.Choice choice = choices.get(0);
                            ChatCompletionChunk.Choice.Delta delta = choice.delta();

                            if (delta != null) {
                                // Handle text content
                                Optional<String> contentOpt = delta.content();
                                if (contentOpt.isPresent() && !contentOpt.get().isEmpty()) {
                                    String content = contentOpt.get();
                                    if (!textBlockStarted.getAndSet(true)) {
                                        events.add(formatSSE(Map.of(
                                                "type", "content_block_start",
                                                "index", contentBlockIndex.getAndIncrement(),
                                                "content_block", Map.of("type", "text", "text", "")
                                        )));
                                    }
                                    events.add(formatSSE(Map.of(
                                            "type", "content_block_delta",
                                            "index", contentBlockIndex.get() - 1,
                                            "delta", Map.of("type", "text_delta", "text", content)
                                    )));
                                }

                                // Handle tool calls
                                Optional<List<ChatCompletionChunk.Choice.Delta.ToolCall>> toolCallsOpt = delta.toolCalls();
                                if (toolCallsOpt.isPresent()) {
                                    List<ChatCompletionChunk.Choice.Delta.ToolCall> toolCalls = toolCallsOpt.get();
                                    for (ChatCompletionChunk.Choice.Delta.ToolCall toolCall : toolCalls) {
                                        int toolIdx = (int) toolCall.index();
                                        
                                        // New tool call starting
                                        Optional<String> idOpt = toolCall.id();
                                        if (idOpt.isPresent() && toolIdx != currentToolIndex.get()) {
                                            // Close previous text block if open
                                            if (textBlockStarted.get()) {
                                                events.add(formatSSE(Map.of(
                                                        "type", "content_block_stop",
                                                        "index", contentBlockIndex.get() - 1
                                                )));
                                                textBlockStarted.set(false);
                                            }
                                            
                                            // Close previous tool call if any
                                            if (currentToolIndex.get() >= 0 && currentToolId.get() != null) {
                                                // Save previous tool call info
                                                collectedToolCalls.get().add(new ToolCallInfo(
                                                        currentToolId.get(),
                                                        currentToolName.get(),
                                                        currentToolArgs.get().toString()
                                                ));
                                                
                                                events.add(formatSSE(Map.of(
                                                        "type", "content_block_stop",
                                                        "index", contentBlockIndex.get() - 1
                                                )));
                                            }
                                            
                                            currentToolIndex.set(toolIdx);
                                            currentToolId.set(idOpt.get());
                                            
                                            Optional<ChatCompletionChunk.Choice.Delta.ToolCall.Function> funcOpt = toolCall.function();
                                            String funcName = funcOpt.flatMap(ChatCompletionChunk.Choice.Delta.ToolCall.Function::name).orElse("");
                                            currentToolName.set(funcName);
                                            currentToolArgs.set(new StringBuilder());
                                            
                                            // Send content_block_start for tool_use
                                            events.add(formatSSE(Map.of(
                                                    "type", "content_block_start",
                                                    "index", contentBlockIndex.getAndIncrement(),
                                                    "content_block", Map.of(
                                                            "type", "tool_use",
                                                            "id", currentToolId.get(),
                                                            "name", currentToolName.get(),
                                                            "input", Map.of()
                                                    )
                                            )));
                                        }
                                        
                                        // Accumulate tool arguments
                                        Optional<ChatCompletionChunk.Choice.Delta.ToolCall.Function> funcOpt = toolCall.function();
                                        if (funcOpt.isPresent()) {
                                            Optional<String> argsOpt = funcOpt.get().arguments();
                                            if (argsOpt.isPresent()) {
                                                String args = argsOpt.get();
                                                currentToolArgs.get().append(args);
                                                events.add(formatSSE(Map.of(
                                                        "type", "content_block_delta",
                                                        "index", contentBlockIndex.get() - 1,
                                                        "delta", Map.of("type", "input_json_delta", "partial_json", args)
                                                )));
                                            }
                                        }
                                    }
                                }

                                // Handle finish reason
                                Optional<ChatCompletionChunk.Choice.FinishReason> finishReasonOpt = choice.finishReason();
                                if (finishReasonOpt.isPresent()) {
                                    // Save last tool call if any
                                    if (currentToolIndex.get() >= 0 && currentToolId.get() != null) {
                                        collectedToolCalls.get().add(new ToolCallInfo(
                                                currentToolId.get(),
                                                currentToolName.get(),
                                                currentToolArgs.get().toString()
                                        ));
                                        
                                        events.add(formatSSE(Map.of(
                                                "type", "content_block_stop",
                                                "index", contentBlockIndex.get() - 1
                                        )));
                                        currentToolIndex.set(-1);
                                    }
                                }
                            }
                        }

                        // Emit all events
                        for (String event : events) {
                            sink.next(event);
                        }
                    });

                    // Send final events
                    if (textBlockStarted.get()) {
                        sink.next(formatSSE(Map.of(
                                "type", "content_block_stop",
                                "index", contentBlockIndex.get() - 1
                        )));
                    }
                    
                    // Build message_delta with nullable stop_sequence
                    Map<String, Object> deltaContent = new HashMap<>();
                    deltaContent.put("stop_reason", "end_turn");
                    deltaContent.put("stop_sequence", null);
                    
                    Map<String, Object> messageDelta = new HashMap<>();
                    messageDelta.put("type", "message_delta");
                    messageDelta.put("delta", deltaContent);
                    messageDelta.put("usage", Map.of("output_tokens", 0));
                    
                    sink.next(formatSSE(messageDelta));
                    sink.next(formatSSE(Map.of("type", "message_stop")));
                    
                    // Record metrics
                    metricsService.recordStreamingToolCalls(userId, collectedToolCalls.get());
                    
                    sink.complete();
                }
            } catch (Exception e) {
                log.error("Error during streaming request: {}", e.getMessage(), e);
                sink.error(e);
            }
        }, reactor.core.publisher.FluxSink.OverflowStrategy.BUFFER)
        .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Build ChatCompletionCreateParams from AnthropicRequest
     */
    private ChatCompletionCreateParams buildChatCompletionParams(AnthropicRequest anthropicRequest) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(anthropicRequest.getModel());

        // Add system message if present
        String systemText = extractSystemText(anthropicRequest.getSystem());
        if (systemText != null && !systemText.isEmpty()) {
            builder.addSystemMessage(systemText);
        }

        // Convert messages
        if (anthropicRequest.getMessages() != null) {
            for (AnthropicMessage msg : anthropicRequest.getMessages()) {
                addMessage(builder, msg);
            }
        }

        // Set optional parameters
        if (anthropicRequest.getMaxTokens() != null) {
            builder.maxTokens(anthropicRequest.getMaxTokens().longValue());
        }
        if (anthropicRequest.getTemperature() != null) {
            builder.temperature(anthropicRequest.getTemperature());
        }
        if (anthropicRequest.getTopP() != null) {
            builder.topP(anthropicRequest.getTopP());
        }
        if (anthropicRequest.getStopSequences() != null && !anthropicRequest.getStopSequences().isEmpty()) {
            builder.stop(ChatCompletionCreateParams.Stop.ofStrings(anthropicRequest.getStopSequences()));
        }

        // Convert tools
        if (anthropicRequest.getTools() != null && !anthropicRequest.getTools().isEmpty()) {
            for (AnthropicTool tool : anthropicRequest.getTools()) {
                ChatCompletionFunctionTool functionTool = ChatCompletionFunctionTool.builder()
                        .function(FunctionDefinition.builder()
                                .name(tool.getName())
                                .description(tool.getDescription() != null ? tool.getDescription() : "")
                                .parameters(FunctionParameters.builder()
                                        .putAllAdditionalProperties(tool.getInputSchema() != null ? 
                                                convertToJsonValueMap(tool.getInputSchema()) : Map.of())
                                        .build())
                                .build())
                        .build();
                builder.addTool(ChatCompletionTool.ofFunction(functionTool));
            }
        }

        return builder.build();
    }

    /**
     * Add message to builder
     */
    private void addMessage(ChatCompletionCreateParams.Builder builder, AnthropicMessage msg) {
        Object content = msg.getContent();
        String role = msg.getRole();

        if (content instanceof String) {
            if ("user".equals(role)) {
                builder.addUserMessage((String) content);
            } else if ("assistant".equals(role)) {
                builder.addAssistantMessage((String) content);
            }
        } else if (content instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> contentList = (List<Map<String, Object>>) content;
            
            StringBuilder textContent = new StringBuilder();
            List<ChatCompletionMessageToolCall> toolCalls = new ArrayList<>();
            
            for (Map<String, Object> item : contentList) {
                String type = (String) item.get("type");
                
                if ("text".equals(type)) {
                    textContent.append(item.get("text"));
                } else if ("tool_use".equals(type)) {
                    String id = (String) item.get("id");
                    String name = (String) item.get("name");
                    Object input = item.get("input");
                    String arguments;
                    try {
                        arguments = objectMapper.writeValueAsString(input);
                    } catch (JsonProcessingException e) {
                        arguments = "{}";
                    }
                    
                    // Build function tool call
                    ChatCompletionMessageFunctionToolCall functionToolCall = 
                            ChatCompletionMessageFunctionToolCall.builder()
                                    .id(id)
                                    .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                            .name(name)
                                            .arguments(arguments)
                                            .build())
                                    .build();
                    toolCalls.add(ChatCompletionMessageToolCall.ofFunction(functionToolCall));
                } else if ("tool_result".equals(type)) {
                    String toolUseId = (String) item.get("tool_use_id");
                    Object resultContent = item.get("content");
                    String toolResultText;
                    
                    if (resultContent instanceof String) {
                        toolResultText = (String) resultContent;
                    } else {
                        try {
                            toolResultText = objectMapper.writeValueAsString(resultContent);
                        } catch (JsonProcessingException e) {
                            toolResultText = "";
                        }
                    }
                    
                    builder.addMessage(ChatCompletionToolMessageParam.builder()
                            .toolCallId(toolUseId)
                            .content(toolResultText)
                            .build());
                }
            }
            
            // Add text or assistant message with tool calls
            if (!textContent.isEmpty() || !toolCalls.isEmpty()) {
                if ("user".equals(role)) {
                    builder.addUserMessage(textContent.toString());
                } else if ("assistant".equals(role)) {
                    if (toolCalls.isEmpty()) {
                        builder.addAssistantMessage(textContent.toString());
                    } else {
                        builder.addMessage(ChatCompletionAssistantMessageParam.builder()
                                .content(textContent.toString())
                                .toolCalls(toolCalls)
                                .build());
                    }
                }
            }
        }
    }

    /**
     * Convert ChatCompletion to AnthropicResponse
     */
    private AnthropicResponse convertToAnthropicResponse(ChatCompletion completion, String requestModel) {
        List<ChatCompletion.Choice> choices = completion.choices();
        if (choices.isEmpty()) {
            return null;
        }

        ChatCompletion.Choice choice = choices.get(0);
        ChatCompletionMessage message = choice.message();

        List<AnthropicContent> content = new ArrayList<>();

        // Convert text content
        Optional<String> textContentOpt = message.content();
        if (textContentOpt.isPresent() && !textContentOpt.get().isEmpty()) {
            content.add(AnthropicContent.builder()
                    .type("text")
                    .text(textContentOpt.get())
                    .build());
        }

        // Convert tool calls
        Optional<List<ChatCompletionMessageToolCall>> toolCallsOpt = message.toolCalls();
        if (toolCallsOpt.isPresent()) {
            List<ChatCompletionMessageToolCall> toolCalls = toolCallsOpt.get();
            for (ChatCompletionMessageToolCall toolCall : toolCalls) {
                // Use function() to get the function tool call
                Optional<ChatCompletionMessageFunctionToolCall> funcToolCallOpt = toolCall.function();
                if (funcToolCallOpt.isPresent()) {
                    ChatCompletionMessageFunctionToolCall funcToolCall = funcToolCallOpt.get();
                    
                    Object input;
                    try {
                        input = objectMapper.readValue(funcToolCall.function().arguments(), Map.class);
                    } catch (JsonProcessingException e) {
                        input = Map.of();
                    }

                    content.add(AnthropicContent.builder()
                            .type("tool_use")
                            .id(funcToolCall.id())
                            .name(funcToolCall.function().name())
                            .input(input)
                            .build());
                }
            }
        }

        // Convert stop reason
        String stopReason = convertFinishReasonToStopReason(choice.finishReason());

        // Convert usage
        AnthropicUsage usage = null;
        Optional<CompletionUsage> usageOpt = completion.usage();
        if (usageOpt.isPresent()) {
            CompletionUsage u = usageOpt.get();
            usage = AnthropicUsage.builder()
                    .inputTokens((int) u.promptTokens())
                    .outputTokens((int) u.completionTokens())
                    .build();
        }

        return AnthropicResponse.builder()
                .id(completion.id())
                .type("message")
                .role("assistant")
                .content(content)
                .model(requestModel)
                .stopReason(stopReason)
                .usage(usage)
                .build();
    }

    /**
     * Convert OpenAI finish_reason to Anthropic stop_reason
     */
    private String convertFinishReasonToStopReason(ChatCompletion.Choice.FinishReason finishReason) {
        if (finishReason == null) {
            return "end_turn";
        }
        
        String reasonStr = finishReason.toString();
        if (reasonStr.contains("STOP") || reasonStr.contains("stop")) {
            return "end_turn";
        } else if (reasonStr.contains("LENGTH") || reasonStr.contains("length")) {
            return "max_tokens";
        } else if (reasonStr.contains("TOOL") || reasonStr.contains("tool")) {
            return "tool_use";
        } else if (reasonStr.contains("CONTENT_FILTER") || reasonStr.contains("content_filter")) {
            return "end_turn";
        }
        return "end_turn";
    }

    /**
     * Convert Map to JsonValue map for SDK
     */
    @SuppressWarnings("unchecked")
    private Map<String, com.openai.core.JsonValue> convertToJsonValueMap(Map<String, Object> map) {
        Map<String, com.openai.core.JsonValue> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            result.put(entry.getKey(), com.openai.core.JsonValue.from(entry.getValue()));
        }
        return result;
    }

    /**
     * Extract system prompt text from Object (can be String or List of content blocks)
     */
    @SuppressWarnings("unchecked")
    private String extractSystemText(Object system) {
        if (system == null) {
            return null;
        }
        if (system instanceof String) {
            return (String) system;
        }
        if (system instanceof List) {
            List<?> blocks = (List<?>) system;
            StringBuilder sb = new StringBuilder();
            for (Object block : blocks) {
                if (block instanceof Map) {
                    Map<String, Object> map = (Map<String, Object>) block;
                    Object text = map.get("text");
                    if (text != null) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(text.toString());
                    }
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        }
        return system.toString();
    }

    /**
     * Format data as SSE event
     */
    private String formatSSE(Map<String, Object> data) {
        try {
            return "event: " + data.get("type") + "\ndata: " + objectMapper.writeValueAsString(data) + "\n\n";
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize SSE data: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Simple class to hold tool call info for metrics
     */
    public record ToolCallInfo(String id, String name, String arguments) {}
}
