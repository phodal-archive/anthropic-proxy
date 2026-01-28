package com.phodal.anthropicproxy.model.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
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
public class AnthropicMessage {
    
    private String role;
    
    private Object content;
    
    // Helper method to get content as string
    public String getContentAsString() {
        if (content instanceof String) {
            return (String) content;
        }
        return null;
    }
    
    // Helper method to get content as list
    @SuppressWarnings("unchecked")
    public List<AnthropicContent> getContentAsList() {
        if (content instanceof List) {
            return (List<AnthropicContent>) content;
        }
        return null;
    }
}
