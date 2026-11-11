package dev.prbot.config;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Small fixed pool with a bounded queue. When the queue is full the task is rejected (the caller
 * gets a TaskRejectedException) instead of piling up memory behind slow LLM calls.
 */
@Configuration
public class AsyncConfig {

    public static String REVIEW_EXECUTOR = "reviewExecutor";

    @Bean(REVIEW_EXECUTOR)
    ThreadPoolTaskExecutor reviewExecutor(BotProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.executor().threads());
        executor.setMaxPoolSize(props.executor().threads());
        executor.setQueueCapacity(props.executor().queueCapacity());
        executor.setThreadNamePrefix("review-");
        executor.setTaskDecorator(copyLogContext());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    /** Carries the request ID from the webhook thread into the review thread's log lines. */
    static TaskDecorator copyLogContext() {
        return task -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    task.run();
                } finally {
                    MDC.clear();
                }
            };
        };
    }
}
