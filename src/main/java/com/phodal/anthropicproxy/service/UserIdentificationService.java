package com.phodal.anthropicproxy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * Service to identify users from request headers and API keys
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserIdentificationService {

    private final ObjectMapper objectMapper;

    /**
     * Extract user ID from request
     */
    public String identifyUser(HttpServletRequest request) {
        // Try different identification methods
        
        // 1. Check for x-api-key header (Anthropic style)
        String apiKey = request.getHeader("x-api-key");
        if (apiKey != null && !apiKey.isEmpty()) {
            String userId = extractUserFromApiKey(apiKey);
            if (userId != null) {
                log.debug("Identified user from x-api-key: {}", userId);
                return userId;
            }
        }
        
        // 2. Check Authorization header
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && !authHeader.isEmpty()) {
            String userId = extractUserFromAuthHeader(authHeader);
            if (userId != null) {
                log.debug("Identified user from Authorization header: {}", userId);
                return userId;
            }
        }
        
        // 3. Check for custom user header
        String customUser = request.getHeader("X-User-Id");
        if (customUser != null && !customUser.isEmpty()) {
            log.debug("Identified user from X-User-Id header: {}", customUser);
            return customUser;
        }
        
        // 4. Check for Anthropic-specific headers
        String anthropicUser = request.getHeader("anthropic-metadata");
        if (anthropicUser != null && !anthropicUser.isEmpty()) {
            String userId = parseAnthropicMetadata(anthropicUser);
            if (userId != null) {
                log.debug("Identified user from anthropic-metadata: {}", userId);
                return userId;
            }
        }
        
        // 5. Fallback to IP address
        String clientIp = getClientIp(request);
        log.debug("Falling back to IP address for user identification: {}", clientIp);
        return "ip:" + clientIp;
    }

    /**
     * Extract API key from request for forwarding
     */
    public String extractApiKey(HttpServletRequest request) {
        // Try x-api-key header first (Anthropic style)
        String apiKey = request.getHeader("x-api-key");
        if (apiKey != null && !apiKey.isEmpty()) {
            return apiKey;
        }
        
        // Try Authorization header
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        
        return null;
    }

    /**
     * Collect all headers for logging
     */
    public Map<String, String> collectHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            // Don't log sensitive data
            if (name.equalsIgnoreCase("authorization") || name.equalsIgnoreCase("x-api-key")) {
                headers.put(name, "[REDACTED]");
            } else {
                headers.put(name, request.getHeader(name));
            }
        }
        
        return headers;
    }

    /**
     * Extract user info from JWT-style API key
     */
    private String extractUserFromApiKey(String apiKey) {
        try {
            // Check if it's a JWT token
            if (apiKey.contains(".")) {
                String[] parts = apiKey.split("\\.");
                if (parts.length >= 2) {
                    String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
                    Map<String, Object> claims = objectMapper.readValue(payload, Map.class);
                    
                    // Try different claim fields
                    if (claims.containsKey("UserName")) {
                        return (String) claims.get("UserName");
                    }
                    if (claims.containsKey("user_name")) {
                        return (String) claims.get("user_name");
                    }
                    if (claims.containsKey("username")) {
                        return (String) claims.get("username");
                    }
                    if (claims.containsKey("sub")) {
                        return (String) claims.get("sub");
                    }
                    if (claims.containsKey("SubjectID")) {
                        return (String) claims.get("SubjectID");
                    }
                    if (claims.containsKey("GroupName")) {
                        return (String) claims.get("GroupName");
                    }
                }
            }
            
            // Return first 8 chars of API key as identifier
            if (apiKey.length() > 8) {
                return "key:" + apiKey.substring(0, 8);
            }
        } catch (Exception e) {
            log.debug("Failed to parse API key: {}", e.getMessage());
        }
        
        return null;
    }

    /**
     * Extract user info from Authorization header
     */
    private String extractUserFromAuthHeader(String authHeader) {
        if (authHeader.startsWith("Bearer ")) {
            return extractUserFromApiKey(authHeader.substring(7));
        }
        return null;
    }

    /**
     * Parse Anthropic metadata header
     */
    private String parseAnthropicMetadata(String metadata) {
        try {
            Map<String, Object> metaMap = objectMapper.readValue(metadata, Map.class);
            if (metaMap.containsKey("user_id")) {
                return (String) metaMap.get("user_id");
            }
        } catch (Exception e) {
            log.debug("Failed to parse anthropic-metadata: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Get client IP address
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        
        return request.getRemoteAddr();
    }
}
