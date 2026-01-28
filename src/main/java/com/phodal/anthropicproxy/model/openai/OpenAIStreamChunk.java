package com.phodal.anthropicproxy.model.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * OpenAI Streaming Chunk Response
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAIStreamChunk {
    
    private String id;
    
    private String object;
    
    private Long created;
    
    private String model;
    
    private List<OpenAIChoice> choices;
    
    private OpenAIUsage usage;
}
