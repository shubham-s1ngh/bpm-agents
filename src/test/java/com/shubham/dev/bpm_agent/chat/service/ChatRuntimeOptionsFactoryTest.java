package com.shubham.dev.bpm_agent.chat.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.azure.openai.AzureOpenAiResponseFormat;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRuntimeOptionsFactoryTest {

    @Test
    void buildsOllamaJsonAndTextOptions() {
        ChatRuntimeOptionsFactory factory = new ChatRuntimeOptionsFactory("ollama");

        OllamaChatOptions jsonOptions = assertInstanceOf(OllamaChatOptions.class, factory.jsonModeOptions());
        OllamaChatOptions textOptions = assertInstanceOf(OllamaChatOptions.class, factory.textModeOptions().orElseThrow());

        assertEquals("json", jsonOptions.getFormat());
        assertEquals(null, textOptions.getFormat());
    }

    @Test
    void buildsOpenAiJsonOptionsAndSkipsExplicitTextOptions() {
        ChatRuntimeOptionsFactory factory = new ChatRuntimeOptionsFactory("openai");

        OpenAiChatOptions jsonOptions = assertInstanceOf(OpenAiChatOptions.class, factory.jsonModeOptions());

        assertEquals(ResponseFormat.Type.JSON_OBJECT, jsonOptions.getResponseFormat().getType());
        assertTrue(factory.textModeOptions().isEmpty());
    }

    @Test
    void buildsAzureOpenAiJsonOptionsAndSkipsExplicitTextOptions() {
        ChatRuntimeOptionsFactory factory = new ChatRuntimeOptionsFactory("azure-openai");

        AzureOpenAiChatOptions jsonOptions = assertInstanceOf(AzureOpenAiChatOptions.class, factory.jsonModeOptions());

        assertEquals(AzureOpenAiResponseFormat.Type.JSON_OBJECT, jsonOptions.getResponseFormat().getType());
        assertTrue(factory.textModeOptions().isEmpty());
    }
}
