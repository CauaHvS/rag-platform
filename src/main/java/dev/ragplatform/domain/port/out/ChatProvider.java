package dev.ragplatform.domain.port.out;

/**
 * Porta de saída para geração de texto via LLM.
 *
 * Implementações disponíveis:
 *   - GroqChatProvider   (prod — llama-3.3-70b-versatile)
 *   - FakeChatProvider   (testes — resposta fixa, sem rede)
 *
 * O system prompt é tratado como código: versionado, em arquivo, testável.
 * A chave de API vive no backend; o frontend nunca a vê.
 */
public interface ChatProvider {

    /**
     * Envia um prompt ao LLM e retorna a resposta completa (não streaming).
     *
     * @param systemPrompt instrução de sistema versionada
     * @param userMessage  mensagem do usuário (a pergunta)
     * @return resposta gerada pelo modelo
     */
    String chat(String systemPrompt, String userMessage);
}
