package com.jacid.ratelimit.redis;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.jacid.ratelimit.RateLimitPolicy;
import com.jacid.ratelimit.RateLimiter;

/** Varias instancias, varios hilos por instancia, todos a la vez contra la misma clave. */
final class ConcurrentLoad {

    static final int INSTANCES = 3;
    private static final int THREADS_PER_INSTANCE = 6;
    private static final int ATTEMPTS_PER_THREAD = 25;

    record Outcome(int attempts, int allowed, int rejected) {}

    private ConcurrentLoad() {}

    static Outcome run(List<RateLimiter> instances, RateLimitPolicy policy, String key) throws Exception {
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(instances.size() * THREADS_PER_INSTANCE);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (RateLimiter instance : instances) {
                for (int t = 0; t < THREADS_PER_INSTANCE; t++) {
                    tasks.add(pool.submit(() -> {
                        go.await();
                        for (int i = 0; i < ATTEMPTS_PER_THREAD; i++) {
                            if (instance.tryAcquire(policy, key).allowed()) {
                                allowed.incrementAndGet();
                            } else {
                                rejected.incrementAndGet();
                            }
                        }
                        return null;
                    }));
                }
            }
            go.countDown();
            for (Future<?> task : tasks) {
                task.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return new Outcome(instances.size() * THREADS_PER_INSTANCE * ATTEMPTS_PER_THREAD, allowed.get(), rejected.get());
    }
}
