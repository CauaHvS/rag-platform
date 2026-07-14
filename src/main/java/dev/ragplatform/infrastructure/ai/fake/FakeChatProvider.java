package dev.ragplatform.infrastructure.ai.fake;

import dev.ragplatform.domain.port.out.ChatProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Adapter fake do ChatProvider.
 *
 * Ativo quando nenhum outro ChatProvider está registrado no contexto Spring.
 * Em prod, este bean é substituído automaticamente pelo GroqChatProvider.
 *
 * Retorna uma resposta fixa — sem rede, sem cota, sem chave de API.
 */
@Component
@ConditionalOnMissingBean(ChatProvider.class)
public class FakeChatProvider implements ChatProvider {

    @Override
    public String chat(String systemPrompt, String userMessage) {
        return "[FakeChatProvider] Resposta simulada para: " + userMessage;
    }
}
