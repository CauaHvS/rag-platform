package dev.ragplatform.infrastructure.security;

/**
 * Titular do userId corrente para Row Level Security.
 *
 * Usa InheritableThreadLocal para que virtual threads (SSE streaming) herdem
 * o userId do thread pai (thread HTTP do Tomcat), sem necessidade de propagar
 * explicitamente o contexto.
 *
 * Ciclo de vida: definido pelo RlsFilter no início de cada requisição HTTP
 * e limpo no finally após a resposta. Null em contextos sem requisição HTTP
 * (jobs de background, migrations) — nesse caso o RLS deixa todas as linhas visíveis.
 */
public final class RlsContext {

    private static final InheritableThreadLocal<String> HOLDER = new InheritableThreadLocal<>();

    private RlsContext() {}

    public static void set(String userId) {
        HOLDER.set(userId);
    }

    /** Retorna o UUID do usuário corrente como String, ou null em contextos sem usuário. */
    public static String get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
