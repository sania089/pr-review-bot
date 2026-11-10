package dev.prbot.llm;

import dev.prbot.config.BotProperties;
import dev.prbot.http.Retrier.Sleeper;
import dev.prbot.persistence.LlmUsageRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * Keeps model usage inside the provider's free tier: a daily request cap (stored in the database,
 * so it survives restarts) and an even spacing between requests under the per-minute limit.
 */
@Component
public class QuotaGuard {

    private final LlmUsageRepository usage;
    private final int dailyLimit;
    private final long intervalNanos;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private long nextSlotNanos;

    public QuotaGuard(BotProperties props, LlmUsageRepository usage) {
        this(
                props.quota(),
                usage,
                Clock.system(ZoneId.of(props.quota().resetZone())),
                System::nanoTime,
                d -> Thread.sleep(d.toMillis(), (int) (d.toNanos() % 1_000_000)));
    }

    QuotaGuard(
            BotProperties.Quota quota,
            LlmUsageRepository usage,
            Clock clock,
            LongSupplier nanoTime,
            Sleeper sleeper) {
        this.usage = usage;
        this.dailyLimit = quota.dailyLimit();
        this.intervalNanos = Duration.ofMinutes(1).toNanos() / quota.requestsPerMinute();
        this.clock = clock;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
        this.nextSlotNanos = nanoTime.getAsLong();
    }

    /** Claims today's requests for a whole review up front, so it never stops half way. */
    public void reserve(int requests) {
        if (!usage.tryReserve(LocalDate.now(clock), requests, dailyLimit)) {
            throw new QuotaExceededException(
                    "daily model request limit of "
                            + dailyLimit
                            + " reached; reviews resume when the quota resets");
        }
    }

    /** Blocks until this request's turn, so requests are spread evenly under the per-minute cap. */
    public void awaitSlot() {
        long wait = claimSlot();
        if (wait > 0) {
            try {
                sleeper.sleep(Duration.ofNanos(wait));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting for a model request slot");
            }
        }
    }

    private synchronized long claimSlot() {
        long now = nanoTime.getAsLong();
        long start = Math.max(now, nextSlotNanos);
        nextSlotNanos = start + intervalNanos;
        return start - now;
    }
}
