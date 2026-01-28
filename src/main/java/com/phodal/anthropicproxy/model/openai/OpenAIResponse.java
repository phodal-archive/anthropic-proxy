package com.phodal.anthropicproxy.model.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * OpenAI Chat Completion Response
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAIResponse {
    
    private String id;
    
    private String object;
    
    private Long created;
    
    private String model;
    
    private List<OpenAIChoice> choices;
    
    private OpenAIUsage usage;
    
    @JsonProperty("system_fingerprint")
    private String systemFingerprint;
}
