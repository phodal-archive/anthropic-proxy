package com.phodal.anthropicproxy.controller;

import com.phodal.anthropicproxy.model.anthropic.AnthropicRequest;
import com.phodal.anthropicproxy.model.anthropic.AnthropicResponse;
import com.phodal.anthropicproxy.service.MetricsService;
import com.phodal.anthropicproxy.service.OpenAISdkService;
import com.phodal.anthropicproxy.service.UserIdentificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

/**
 * Controller to handle Anthropic API proxy requests
 * Uses official OpenAI Java SDK for API calls
 */
@Slf4j
@RestController
@RequestMapping("/anthropic")
@RequiredArgsConstructor
public class AnthropicProxyController {

    private final OpenAISdkService sdkService;
    private final MetricsService metricsService;
    private final UserIdentificationService userIdentificationService;

    /**
     * Handle Anthropic Messages API requests
     */
    @PostMapping(value = "/v1/messages")
    public ResponseEntity<?> handleMessages(
            @RequestBody AnthropicRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) throws IOException {

        String userId = userIdentificationService.identifyUser(httpRequest);
        String apiKey = userIdentificationService.extractApiKey(httpRequest);
        Map<String, String> headers = userIdentificationService.collectHeaders(httpRequest);

        log.info("Received request from user: {}, model: {}, stream: {}", userId, request.getModel(), request.getStream());

        // Record the request
        metricsService.recordRequest(userId, request, headers);

        if (apiKey == null || apiKey.isEmpty()) {
            log.error("No API key provided");
            return ResponseEntity.status(401).body(Map.of(
                    "type", "error",
                    "error", Map.of(
                            "type", "authentication_error",
                            "message", "No API key provided"
                    )
            ));
        }

        // Handle streaming vs non-streaming
//        if (Boolean.TRUE.equals(request.getStream())) {
        handleStreamingRequest(request, userId, apiKey, httpResponse);
        return null;
//            return null; // Response already written
//        } else {
//            return handleNonStreamingRequest(request, userId, apiKey);
//        }
    }

    /**
     * Handle non-streaming request
     */
    private ResponseEntity<?> handleNonStreamingRequest(
            AnthropicRequest request, String userId, String apiKey) {
        try {
            AnthropicResponse response = sdkService.sendRequest(request, userId, apiKey).block();
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error handling non-streaming request: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "type", "error",
                    "error", Map.of(
                            "type", "api_error",
                            "message", e.getMessage() != null ? e.getMessage() : "Unknown error"
                    )
            ));
        }
    }

    /**
     * Handle streaming request - writes directly to response
     */
    private void handleStreamingRequest(
            AnthropicRequest request, String userId, String apiKey,
            HttpServletResponse httpResponse) throws IOException {

        httpResponse.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        httpResponse.setCharacterEncoding("UTF-8");
        httpResponse.setHeader("Cache-Control", "no-cache");
        httpResponse.setHeader("Connection", "keep-alive");
        httpResponse.setHeader("X-Accel-Buffering", "no");

        PrintWriter writer = httpResponse.getWriter();

        try {
            sdkService.sendStreamingRequest(request, userId, apiKey)
                    .doOnNext(chunk -> {
                        writer.print(chunk);
                        writer.flush();
                    })
                    .doOnError(e -> {
                        log.error("Error in streaming: {}", e.getMessage());
                        writer.print("event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}}\n\n");
                        writer.flush();
                    })
                    .blockLast();
        } catch (Exception e) {
            log.error("Error handling streaming request: {}", e.getMessage(), e);
            writer.print("event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"" + (e.getMessage() != null ? e.getMessage().replace("\"", "\\\"") : "Unknown error") + "\"}}\n\n");
            writer.flush();
        }
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "healthy"));
    }
}
