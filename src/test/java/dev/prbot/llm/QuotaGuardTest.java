package dev.prbot.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.prbot.config.BotProperties.Quota;
import dev.prbot.persistence.LlmUsageRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class QuotaGuardTest {

    private final LlmUsageRepository usage = mock(LlmUsageRepository.class);
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final List<Duration> sleeps = new ArrayList<>();

    private QuotaGuard guard(int rpm, int daily, Clock clock) {
        return new QuotaGuard(
                new Quota(rpm, daily, "America/Los_Angeles"),
                usage,
                clock,
                nanos::get,
                d -> {
                    sleeps.add(d);
                    nanos.addAndGet(d.toNanos()); // time passes while "sleeping"
                });
    }

    @Test
    void spacesRequestsEvenlyUnderTheRateLimit() {
        QuotaGuard guard = guard(12, 450, Clock.systemUTC()); // one request every 5 seconds

        guard.awaitSlot(); // the first one goes straight through
        guard.awaitSlot();
        guard.awaitSlot();

        assertThat(sleeps).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    @Test
    void doesNotWaitWhenEnoughTimeHasPassed() {
        QuotaGuard guard = guard(12, 450, Clock.systemUTC());

        guard.awaitSlot();
        nanos.addAndGet(Duration.ofSeconds(30).toNanos());
        guard.awaitSlot();

        assertThat(sleeps).isEmpty();
    }

    @Test
    void reservesAgainstTheDayInTheResetZone() {
        // 2026-11-10 03:00 UTC is still 2026-11-09 in Pacific time.
        Clock clock =
                Clock.fixed(
                        Instant.parse("2026-11-10T03:00:00Z"), ZoneId.of("America/Los_Angeles"));
        when(usage.tryReserve(any(), anyInt(), anyInt())).thenReturn(true);

        guard(12, 450, clock).reserve(3);

        verify(usage).tryReserve(LocalDate.of(2026, 11, 9), 3, 450);
    }

    @Test
    void throwsWhenTheDailyLimitIsReached() {
        when(usage.tryReserve(any(), anyInt(), anyInt())).thenReturn(false);

        assertThatThrownBy(() -> guard(12, 450, Clock.systemUTC()).reserve(1))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("450");
    }
}
