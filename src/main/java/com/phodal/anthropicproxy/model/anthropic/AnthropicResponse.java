package com.phodal.anthropicproxy.model.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * Anthropic Messages API Response
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicResponse {
    
    private String id;
    
    private String type;
    
    private String role;
    
    private List<AnthropicContent> content;
    
    private String model;
    
    @JsonProperty("stop_reason")
    private String stopReason;
    
    @JsonProperty("stop_sequence")
    private String stopSequence;
    
    private AnthropicUsage usage;
}
