package com.phodal.anthropicproxy.model.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Anthropic Streaming Events
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicStreamEvent {
    
    private String type;
    
    private AnthropicResponse message;
    
    private Integer index;
    
    @JsonProperty("content_block")
    private AnthropicContent contentBlock;
    
    private AnthropicStreamDelta delta;
    
    private AnthropicUsage usage;
}
