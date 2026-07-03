package com.dataclub.llmcascade.provider;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

/**
 * Provider-Verhalten bei Fehlern — Fokus: ein nicht erreichbarer Server
 * (Connection refused / Timeout) muss als {@link LlmException.Type#SERVER_ERROR}
 * hochkommen, damit die Cascade-Schleife zum naechsten Modell ueberspringt.
 * Gilt fuer JEDEN Pool: nicht nur Ollama-lokal, auch ein zugeordneter
 * Cloud-/self-hosted-Inferenz-Server kann wegbrechen.
 */
class OpenAiCompatProviderTest {

    @Test
    void generate_serverUnreachable_mapsToServerErrorForFailover() {
        OpenAiCompatProvider provider = new OpenAiCompatProvider("http://server-aus:11434/v1", false);
        MockRestServiceServer server = MockRestServiceServer.createServer(provider.restTemplate);
        server.expect(requestTo("http://server-aus:11434/v1/chat/completions"))
              .andRespond(withException(new ConnectException("Connection refused")));

        LlmException ex = catchThrowableOfType(
            () -> provider.generate("hi", "qwen2.5-coder:7b", ""),
            LlmException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getType()).isEqualTo(LlmException.Type.SERVER_ERROR);
    }

    @Test
    void generateChat_serverUnreachable_mapsToServerErrorForFailover() {
        OpenAiCompatProvider provider = new OpenAiCompatProvider("http://server-aus:11434/v1", false);
        MockRestServiceServer server = MockRestServiceServer.createServer(provider.restTemplate);
        server.expect(requestTo("http://server-aus:11434/v1/chat/completions"))
              .andRespond(withException(new ConnectException("Connection refused")));

        LlmException ex = catchThrowableOfType(
            () -> provider.generateChat(
                java.util.List.of(java.util.Map.of("role", "user", "content", "hi")),
                null, null, "qwen2.5-coder:7b", "", null),
            LlmException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getType()).isEqualTo(LlmException.Type.SERVER_ERROR);
    }

    @Test
    void generate_http500_stillMapsToServerError() {
        OpenAiCompatProvider provider = new OpenAiCompatProvider("http://x:11434/v1", false);
        MockRestServiceServer server = MockRestServiceServer.createServer(provider.restTemplate);
        server.expect(requestTo("http://x:11434/v1/chat/completions"))
              .andRespond(withServerError());

        assertThatThrownBy(() -> provider.generate("hi", "m", ""))
            .isInstanceOf(LlmException.class);
    }
}
