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
public class AnthropicUsage {
    
    @JsonProperty("input_tokens")
    private Integer inputTokens;
    
    @JsonProperty("output_tokens")
    private Integer outputTokens;
    
    @JsonProperty("cache_creation_input_tokens")
    private Integer cacheCreationInputTokens;
    
    @JsonProperty("cache_read_input_tokens")
    private Integer cacheReadInputTokens;
}
