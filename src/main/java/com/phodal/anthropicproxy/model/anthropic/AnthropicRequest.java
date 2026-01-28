package com.phodal.anthropicproxy.model.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages API Request
 * https://docs.anthropic.com/en/api/messages
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicRequest {
    
    private String model;
    
    private List<AnthropicMessage> messages;
    
    @JsonProperty("max_tokens")
    private Integer maxTokens;
    
    // metadata can be an object with user_id field
    private Object metadata;
    
    @JsonProperty("stop_sequences")
    private List<String> stopSequences;
    
    private Boolean stream;
    
    // system can be String or List<Map<String, Object>> (content blocks)
    private Object system;
    
    private Double temperature;
    
    @JsonProperty("top_k")
    private Integer topK;
    
    @JsonProperty("top_p")
    private Double topP;
    
    private List<AnthropicTool> tools;
    
    @JsonProperty("tool_choice")
    private Object toolChoice;
}
