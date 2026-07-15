package dev.ragplatform.infrastructure.async;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Pool dedicado para jobs de ingestão.
     * Separado do pool padrão do Spring para não bloquear outros @Async.
     * Em produção, ajustar corePoolSize conforme carga esperada de uploads.
     */
    @Bean("ingestionExecutor")
    public Executor ingestionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ingestion-");
        // Se a fila estiver cheia, o chamador executa a tarefa — evita rejeitar uploads
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
