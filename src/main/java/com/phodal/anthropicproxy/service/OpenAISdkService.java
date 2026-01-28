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

    private OpenAIClient createClient(String apiKey) {
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
            
            ChatCompletion completion = client.chat().completions().create(params);
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
                OpenAIClient client = createClient(apiKey);
                ChatCompletionCreateParams params = buildChatCompletionParams(anthropicRequest);
                
                String requestModel = anthropicRequest.getModel();
                String messageId = "msg_" + UUID.randomUUID().toString().replace("-", "");

                // Stream state
                StringBuilder currentToolArgs = new StringBuilder();
                String[] currentToolName = {null};
                String[] currentToolId = {null};
                int[] currentToolIndex = {-1};
                int[] contentBlockIndex = {0};
                boolean[] messageStartSent = {false};
                boolean[] textBlockStarted = {false};
                List<ToolCallInfo> collectedToolCalls = new ArrayList<>();

                try (StreamResponse<ChatCompletionChunk> stream = 
                        client.chat().completions().createStreaming(params)) {
                    
                    stream.stream().forEach(chunk -> {
                        List<String> events = new ArrayList<>();

                        // Send message_start on first chunk
                        if (!messageStartSent[0]) {
                            messageStartSent[0] = true;
                            events.add(formatSSE(Map.of(
                                "type", "message_start",
                                "message", createMessageStartData(messageId, requestModel)
                            )));
                        }

                        if (chunk.choices().isEmpty()) return;
                        
                        ChatCompletionChunk.Choice choice = chunk.choices().get(0);
                        ChatCompletionChunk.Choice.Delta delta = choice.delta();

                        // Handle text content
                        delta.content().filter(c -> !c.isEmpty()).ifPresent(content -> {
                            if (!textBlockStarted[0]) {
                                textBlockStarted[0] = true;
                                events.add(formatSSE(Map.of(
                                    "type", "content_block_start",
                                    "index", contentBlockIndex[0]++,
                                    "content_block", Map.of("type", "text", "text", "")
                                )));
                            }
                            events.add(formatSSE(Map.of(
                                "type", "content_block_delta",
                                "index", contentBlockIndex[0] - 1,
                                "delta", Map.of("type", "text_delta", "text", content)
                            )));
                        });

                        // Handle tool calls
                        delta.toolCalls().ifPresent(toolCalls -> {
                            for (var toolCall : toolCalls) {
                                int toolIdx = (int) toolCall.index();
                                
                                // New tool call starting
                                toolCall.id().ifPresent(id -> {
                                    if (toolIdx != currentToolIndex[0]) {
                                        // Close previous text block
                                        if (textBlockStarted[0]) {
                                            events.add(formatSSE(Map.of(
                                                "type", "content_block_stop",
                                                "index", contentBlockIndex[0] - 1
                                            )));
                                            textBlockStarted[0] = false;
                                        }
                                        
                                        // Save previous tool call
                                        if (currentToolIndex[0] >= 0 && currentToolId[0] != null) {
                                            collectedToolCalls.add(new ToolCallInfo(
                                                currentToolId[0], currentToolName[0], currentToolArgs.toString()));
                                            events.add(formatSSE(Map.of(
                                                "type", "content_block_stop",
                                                "index", contentBlockIndex[0] - 1
                                            )));
                                        }
                                        
                                        currentToolIndex[0] = toolIdx;
                                        currentToolId[0] = id;
                                        currentToolName[0] = toolCall.function()
                                            .flatMap(ChatCompletionChunk.Choice.Delta.ToolCall.Function::name)
                                            .orElse("");
                                        currentToolArgs.setLength(0);
                                        
                                        events.add(formatSSE(Map.of(
                                            "type", "content_block_start",
                                            "index", contentBlockIndex[0]++,
                                            "content_block", Map.of(
                                                "type", "tool_use",
                                                "id", currentToolId[0],
                                                "name", currentToolName[0],
                                                "input", Map.of()
                                            )
                                        )));
                                    }
                                });
                                
                                // Accumulate tool arguments
                                toolCall.function().flatMap(f -> f.arguments()).ifPresent(args -> {
                                    currentToolArgs.append(args);
                                    events.add(formatSSE(Map.of(
                                        "type", "content_block_delta",
                                        "index", contentBlockIndex[0] - 1,
                                        "delta", Map.of("type", "input_json_delta", "partial_json", args)
                                    )));
                                });
                            }
                        });

                        // Handle finish reason
                        choice.finishReason().ifPresent(reason -> {
                            if (currentToolIndex[0] >= 0 && currentToolId[0] != null) {
                                collectedToolCalls.add(new ToolCallInfo(
                                    currentToolId[0], currentToolName[0], currentToolArgs.toString()));
                                events.add(formatSSE(Map.of(
                                    "type", "content_block_stop",
                                    "index", contentBlockIndex[0] - 1
                                )));
                                currentToolIndex[0] = -1;
                            }
                        });

                        events.forEach(sink::next);
                    });

                    // Final events
                    if (textBlockStarted[0]) {
                        sink.next(formatSSE(Map.of(
                            "type", "content_block_stop",
                            "index", contentBlockIndex[0] - 1
                        )));
                    }
                    
                    Map<String, Object> deltaContent = new HashMap<>();
                    deltaContent.put("stop_reason", "end_turn");
                    deltaContent.put("stop_sequence", null);
                    
                    sink.next(formatSSE(Map.of(
                        "type", "message_delta",
                        "delta", deltaContent,
                        "usage", Map.of("output_tokens", 0)
                    )));
                    sink.next(formatSSE(Map.of("type", "message_stop")));
                    
                    metricsService.recordStreamingToolCalls(userId, collectedToolCalls);
                    sink.complete();
                }
            } catch (Exception e) {
                log.error("Error during streaming: {}", e.getMessage(), e);
                sink.error(e);
            }
        }, reactor.core.publisher.FluxSink.OverflowStrategy.BUFFER)
        .subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> createMessageStartData(String messageId, String model) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", messageId);
        data.put("type", "message");
        data.put("role", "assistant");
        data.put("content", List.of());
        data.put("model", model != null ? model : "unknown");
        data.put("stop_reason", null);
        data.put("stop_sequence", null);
        data.put("usage", Map.of("input_tokens", 0, "output_tokens", 0));
        return data;
    }

    private ChatCompletionCreateParams buildChatCompletionParams(AnthropicRequest request) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(request.getModel());

        // System message
        String systemText = extractSystemText(request.getSystem());
        if (systemText != null && !systemText.isEmpty()) {
            builder.addSystemMessage(systemText);
        }

        // Messages
        if (request.getMessages() != null) {
            for (AnthropicMessage msg : request.getMessages()) {
                addMessage(builder, msg);
            }
        }

        // Optional parameters
        if (request.getMaxTokens() != null) {
            builder.maxTokens(request.getMaxTokens().longValue());
        }
        if (request.getTemperature() != null) {
            builder.temperature(request.getTemperature());
        }
        if (request.getTopP() != null) {
            builder.topP(request.getTopP());
        }
        if (request.getStopSequences() != null && !request.getStopSequences().isEmpty()) {
            builder.stop(ChatCompletionCreateParams.Stop.ofStrings(request.getStopSequences()));
        }

        // Tools
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            for (AnthropicTool tool : request.getTools()) {
                builder.addTool(ChatCompletionTool.ofFunction(
                    ChatCompletionFunctionTool.builder()
                        .function(FunctionDefinition.builder()
                            .name(tool.getName())
                            .description(tool.getDescription() != null ? tool.getDescription() : "")
                            .parameters(FunctionParameters.builder()
                                .putAllAdditionalProperties(tool.getInputSchema() != null ? 
                                    convertToJsonValueMap(tool.getInputSchema()) : Map.of())
                                .build())
                            .build())
                        .build()
                ));
            }
        }

        return builder.build();
    }

    private void addMessage(ChatCompletionCreateParams.Builder builder, AnthropicMessage msg) {
        Object content = msg.getContent();
        String role = msg.getRole();

        if (content instanceof String text) {
            if ("user".equals(role)) {
                builder.addUserMessage(text);
            } else if ("assistant".equals(role)) {
                builder.addAssistantMessage(text);
            }
        } else if (content instanceof List<?> contentList) {
            processContentList(builder, role, contentList);
        }
    }

    @SuppressWarnings("unchecked")
    private void processContentList(ChatCompletionCreateParams.Builder builder, String role, List<?> contentList) {
        StringBuilder textContent = new StringBuilder();
        List<ChatCompletionMessageToolCall> toolCalls = new ArrayList<>();

        for (Object item : contentList) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> itemMap = (Map<String, Object>) item;
            String type = (String) itemMap.get("type");

            switch (type) {
                case "text" -> textContent.append(itemMap.get("text"));
                case "tool_use" -> {
                    String arguments;
                    try {
                        arguments = objectMapper.writeValueAsString(itemMap.get("input"));
                    } catch (JsonProcessingException e) {
                        arguments = "{}";
                    }
                    toolCalls.add(ChatCompletionMessageToolCall.ofFunction(
                        ChatCompletionMessageFunctionToolCall.builder()
                            .id((String) itemMap.get("id"))
                            .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                .name((String) itemMap.get("name"))
                                .arguments(arguments)
                                .build())
                            .build()
                    ));
                }
                case "tool_result" -> {
                    Object resultContent = itemMap.get("content");
                    String toolResultText = resultContent instanceof String ? 
                        (String) resultContent : serializeToJson(resultContent);
                    builder.addMessage(ChatCompletionToolMessageParam.builder()
                        .toolCallId((String) itemMap.get("tool_use_id"))
                        .content(toolResultText)
                        .build());
                }
            }
        }

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

    private AnthropicResponse convertToAnthropicResponse(ChatCompletion completion, String requestModel) {
        if (completion.choices().isEmpty()) return null;

        ChatCompletion.Choice choice = completion.choices().get(0);
        ChatCompletionMessage message = choice.message();
        List<AnthropicContent> content = new ArrayList<>();

        // Text content
        message.content().filter(c -> !c.isEmpty()).ifPresent(text ->
            content.add(AnthropicContent.builder().type("text").text(text).build())
        );

        // Tool calls
        message.toolCalls().ifPresent(toolCalls -> {
            for (var toolCall : toolCalls) {
                toolCall.function().ifPresent(func -> {
                    Object input;
                    try {
                        input = objectMapper.readValue(func.function().arguments(), Map.class);
                    } catch (JsonProcessingException e) {
                        input = Map.of();
                    }
                    content.add(AnthropicContent.builder()
                        .type("tool_use")
                        .id(func.id())
                        .name(func.function().name())
                        .input(input)
                        .build());
                });
            }
        });

        AnthropicUsage usage = completion.usage().map(u -> 
            AnthropicUsage.builder()
                .inputTokens((int) u.promptTokens())
                .outputTokens((int) u.completionTokens())
                .build()
        ).orElse(null);

        return AnthropicResponse.builder()
            .id(completion.id())
            .type("message")
            .role("assistant")
            .content(content)
            .model(requestModel)
            .stopReason(convertFinishReason(choice.finishReason()))
            .usage(usage)
            .build();
    }

    private String convertFinishReason(ChatCompletion.Choice.FinishReason reason) {
        if (reason == null) return "end_turn";
        String str = reason.toString().toLowerCase();
        if (str.contains("stop")) return "end_turn";
        if (str.contains("length")) return "max_tokens";
        if (str.contains("tool")) return "tool_use";
        return "end_turn";
    }

    private Map<String, com.openai.core.JsonValue> convertToJsonValueMap(Map<String, Object> map) {
        Map<String, com.openai.core.JsonValue> result = new HashMap<>();
        for (var entry : map.entrySet()) {
            result.put(entry.getKey(), com.openai.core.JsonValue.from(entry.getValue()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private String extractSystemText(Object system) {
        if (system == null) return null;
        if (system instanceof String s) return s;
        if (system instanceof List<?> blocks) {
            StringBuilder sb = new StringBuilder();
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> map) {
                    Object text = ((Map<String, Object>) map).get("text");
                    if (text != null) {
                        if (!sb.isEmpty()) sb.append("\n");
                        sb.append(text);
                    }
                }
            }
            return sb.isEmpty() ? null : sb.toString();
        }
        return system.toString();
    }

    private String formatSSE(Map<String, Object> data) {
        try {
            return "event: " + data.get("type") + "\ndata: " + objectMapper.writeValueAsString(data) + "\n\n";
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize SSE: {}", e.getMessage());
            return "";
        }
    }

    private String serializeToJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    public record ToolCallInfo(String id, String name, String arguments) {}
}
