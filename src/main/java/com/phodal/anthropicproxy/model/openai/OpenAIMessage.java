package com.phodal.anthropicproxy.model.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAIMessage {
    
    private String role;
    
    private Object content;
    
    private String name;
    
    @JsonProperty("tool_calls")
    private List<OpenAIToolCall> toolCalls;
    
    @JsonProperty("tool_call_id")
    private String toolCallId;
    
    // Helper method to get content as string
    public String getContentAsString() {
        if (content instanceof String) {
            return (String) content;
        }
        return null;
    }
    
    // Helper method to get content as list
    @SuppressWarnings("unchecked")
    public List<OpenAIContentPart> getContentAsList() {
        if (content instanceof List) {
            return (List<OpenAIContentPart>) content;
        }
        return null;
    }
}
