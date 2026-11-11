package dev.prbot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.prbot.TestProps;
import dev.prbot.config.BotProperties.Provider;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class AsyncConfigTest {

    private ThreadPoolTaskExecutor executor() {
        // TestProps: 1 thread, queue of 1
        ThreadPoolTaskExecutor e =
                new AsyncConfig().reviewExecutor(TestProps.with(Provider.GEMINI));
        e.initialize();
        return e;
    }

    @Test
    void rejectsWorkOnceThreadsAndQueueAreFull() throws Exception {
        ThreadPoolTaskExecutor executor = executor();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                await(release);
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> {}); // fills the queue

            assertThatThrownBy(() -> executor.execute(() -> {}))
                    .isInstanceOf(TaskRejectedException.class);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void carriesRequestIdIntoTheWorkerThread() throws Exception {
        ThreadPoolTaskExecutor executor = executor();
        AtomicReference<String> seen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        MDC.put(RequestIdFilter.MDC_KEY, "delivery-123");
        try {
            executor.execute(() -> {
                seen.set(MDC.get(RequestIdFilter.MDC_KEY));
                done.countDown();
            });
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(seen.get()).isEqualTo("delivery-123");
        } finally {
            MDC.clear();
            executor.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
