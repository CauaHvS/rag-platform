package dev.ragplatform.infrastructure.ai.fake;

import dev.ragplatform.domain.port.out.ChatProvider;
import org.springframework.stereotype.Component;

import java.util.stream.Stream;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Adapter fake do ChatProvider.
 *
 * Ativo quando app.chat.provider=fake (default) ou quando a propriedade não está definida.
 * Em dev/prod, substituir por GroqChatProvider (CHAT_PROVIDER=groq).
 *
 * Retorna uma resposta fixa — sem rede, sem cota, sem chave de API.
 */
@Component
@ConditionalOnProperty(name = "app.chat.provider", havingValue = "fake", matchIfMissing = true)
public class FakeChatProvider implements ChatProvider {

    @Override
    public String chat(String systemPrompt, String userMessage) {
        return "[FakeChatProvider] Resposta simulada para: " + userMessage;
    }

    @Override
    public Stream<String> stream(String systemPrompt, String userMessage) {
        // Simula tokens em streaming sem rede — divide a resposta em palavras
        String[] words = ("[Fake] Resposta em streaming para: " + userMessage).split("(?<=\\s)|(?=\\s)");
        return Stream.of(words);
    }
}
