package dev.ragplatform.domain.port.out;

import java.util.stream.Stream;

/**
 * Porta de saída para geração de texto via LLM.
 *
 * Duas operações:
 *   chat()   — resposta bloqueante completa (usado em endpoints síncronos).
 *   stream() — Stream<String> lazy de tokens; a conexão fica aberta enquanto
 *              o stream não for consumido e fechado (try-with-resources no caller).
 *
 * Implementações:
 *   - GroqChatProvider   (prod — llama-3.3-70b-versatile via SSE)
 *   - FakeChatProvider   (testes — sem rede, tokens simulados)
 *
 * API key vive no backend; o front só manda a pergunta.
 */
public interface ChatProvider {

    /**
     * Resposta completa e bloqueante.
     */
    String chat(String systemPrompt, String userMessage);

    /**
     * Streaming token a token via SSE do LLM.
     * O caller é responsável por fechar o Stream (try-with-resources).
     */
    Stream<String> stream(String systemPrompt, String userMessage);
}
