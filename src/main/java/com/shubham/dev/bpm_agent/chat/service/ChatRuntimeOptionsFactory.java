package com.shubham.dev.bpm_agent.chat.service;

import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.azure.openai.AzureOpenAiResponseFormat;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * Centralizes provider-specific runtime chat options so the orchestration layer
 * can keep one execution flow across OpenAI-compatible, Azure OpenAI, and
 * Ollama-backed chat models.
 */
@Service
public class ChatRuntimeOptionsFactory {

    private final ChatProvider chatProvider;

    public ChatRuntimeOptionsFactory(@Value("${spring.ai.model.chat:openai}") String configuredChatProvider) {
        this.chatProvider = ChatProvider.from(configuredChatProvider);
    }

    public ChatOptions jsonModeOptions() {
        return switch (chatProvider) {
            case OLLAMA -> OllamaChatOptions.builder().format("json").build();
            case OPENAI -> OpenAiChatOptions.builder()
                    .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build())
                    .build();
            case AZURE_OPENAI -> AzureOpenAiChatOptions.builder()
                    .responseFormat(AzureOpenAiResponseFormat.builder()
                            .type(AzureOpenAiResponseFormat.Type.JSON_OBJECT)
                            .build())
                    .build();
        };
    }

    public Optional<ChatOptions> textModeOptions() {
        return switch (chatProvider) {
            case OLLAMA -> Optional.of(OllamaChatOptions.builder().format((String) null).build());
            case OPENAI, AZURE_OPENAI -> Optional.empty();
        };
    }

    ChatProvider chatProvider() {
        return chatProvider;
    }

    enum ChatProvider {
        OLLAMA,
        OPENAI,
        AZURE_OPENAI;

        static ChatProvider from(String configuredChatProvider) {
            return switch (normalize(configuredChatProvider)) {
                case "ollama" -> OLLAMA;
                case "azure-openai" -> AZURE_OPENAI;
                case "openai", "" -> OPENAI;
                default -> throw new IllegalStateException(
                        "Unsupported spring.ai.model.chat provider `%s`. Expected one of: openai, azure-openai, ollama."
                                .formatted(configuredChatProvider)
                );
            };
        }

        private static String normalize(String configuredChatProvider) {
            return configuredChatProvider == null
                    ? ""
                    : configuredChatProvider.trim().toLowerCase(Locale.ROOT);
        }
    }
}
