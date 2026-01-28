package com.phodal.anthropicproxy.model.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicStreamDelta {
    
    private String type;
    
    private String text;
    
    @JsonProperty("partial_json")
    private String partialJson;
    
    @JsonProperty("stop_reason")
    private String stopReason;
    
    @JsonProperty("stop_sequence")
    private String stopSequence;
}
