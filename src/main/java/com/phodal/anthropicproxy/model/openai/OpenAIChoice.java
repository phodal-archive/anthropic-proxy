package com.phodal.anthropicproxy.model.openai;

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
public class OpenAIChoice {
    
    private Integer index;
    
    private OpenAIMessage message;
    
    private OpenAIMessage delta;
    
    @JsonProperty("finish_reason")
    private String finishReason;
    
    private Object logprobs;
}
