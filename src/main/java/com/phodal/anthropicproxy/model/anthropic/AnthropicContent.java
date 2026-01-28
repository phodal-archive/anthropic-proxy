package com.phodal.anthropicproxy.model.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicContent {
    
    private String type;
    
    private String text;
    
    private String id;
    
    private String name;
    
    private Object input;
    
    @JsonProperty("tool_use_id")
    private String toolUseId;
    
    private String content;
    
    // For image content
    private Map<String, Object> source;
}
