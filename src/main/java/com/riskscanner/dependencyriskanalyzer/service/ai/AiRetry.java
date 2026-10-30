package com.riskscanner.dependencyriskanalyzer.service.ai;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Retries an AI call that failed because the provider is rate limiting or temporarily overloaded (HTTP 429, 502-504,
 * timeouts), with exponential backoff. Other failures (bad key, invalid request) are not retried.
 *
 * <p>If the error text contains a {@code Retry-After} value (seconds) it is honored, up to a limit.
 */
final class AiRetry {

    /** Pauses between attempts; replaceable in tests. */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    static final int DEFAULT_ATTEMPTS = 3;
    static final long DEFAULT_BASE_DELAY_MILLIS = 1000;
    private static final long MAX_DELAY_MILLIS = 30_000;
    private static final Pattern RETRY_AFTER = Pattern.compile("retry[- ]after\\D{0,5}(\\d{1,3})", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATUS = Pattern.compile("\\b(429|500|502|503|504|529)\\b");

    private AiRetry() {
    }

    static <T> T call(Supplier<T> action) {
        return call(action, DEFAULT_ATTEMPTS, DEFAULT_BASE_DELAY_MILLIS, Thread::sleep);
    }

    static <T> T call(Supplier<T> action, int maxAttempts, long baseDelayMillis, Sleeper sleeper) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                last = e;
                if (attempt == maxAttempts || !isRetryable(e)) {
                    throw e;
                }
                try {
                    sleeper.sleep(delayFor(e, attempt, baseDelayMillis));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last; // not reachable for maxAttempts >= 1
    }

    /** True for rate limiting, overload and timeouts. */
    static boolean isRetryable(Throwable e) {
        String text = messages(e).toLowerCase(Locale.ROOT);
        return STATUS.matcher(text).find()
                || text.contains("rate limit")
                || text.contains("too many requests")
                || text.contains("overloaded")
                || text.contains("timed out")
                || text.contains("timeout");
    }

    static long delayFor(Throwable e, int attempt, long baseDelayMillis) {
        Matcher m = RETRY_AFTER.matcher(messages(e));
        if (m.find()) {
            return Math.min(MAX_DELAY_MILLIS, Long.parseLong(m.group(1)) * 1000);
        }
        long exponential = baseDelayMillis * (1L << (attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, baseDelayMillis / 2));
        return Math.min(MAX_DELAY_MILLIS, exponential + jitter);
    }

    private static String messages(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append(' ');
            }
        }
        return sb.toString();
    }
}
