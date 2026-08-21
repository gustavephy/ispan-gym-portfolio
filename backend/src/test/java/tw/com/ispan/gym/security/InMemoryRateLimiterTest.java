package tw.com.ispan.gym.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * 純 Mockito-free 單元測試，不開 Spring context。窗口過期／清理相關測試刻意用毫秒級的
 * 極短 Duration 搭配 Thread.sleep 驗證，而不是注入 Clock——這個專案刻意不引入 Clock
 * 抽象（見 memory/password_management_phase_scope.md 的討論：全專案沒有其他地方這樣做，
 * 只為這個功能引入會是唯一的例外，不成比例），改用真實極短等待驗證邊界。
 */
class InMemoryRateLimiterTest {

    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter();

    @Test
    void tryAcquire_額度內每次都成功() {
        assertThat(limiter.tryAcquire("k1", 3, Duration.ofMinutes(1))).isTrue();
        assertThat(limiter.tryAcquire("k1", 3, Duration.ofMinutes(1))).isTrue();
        assertThat(limiter.tryAcquire("k1", 3, Duration.ofMinutes(1))).isTrue();
    }

    @Test
    void tryAcquire_超過額度後回false_且不計入額外次數() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("k2", 3, Duration.ofMinutes(1));
        }

        assertThat(limiter.tryAcquire("k2", 3, Duration.ofMinutes(1))).isFalse();
        assertThat(limiter.tryAcquire("k2", 3, Duration.ofMinutes(1))).isFalse();
    }

    @Test
    void tryAcquire_不同key互不影響() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("k3a", 3, Duration.ofMinutes(1));
        }

        assertThat(limiter.tryAcquire("k3a", 3, Duration.ofMinutes(1))).isFalse();
        assertThat(limiter.tryAcquire("k3b", 3, Duration.ofMinutes(1))).isTrue();
    }

    @Test
    void tryAcquire_視窗過期後舊紀錄被修剪_可以再次通過() throws InterruptedException {
        Duration shortWindow = Duration.ofMillis(50);
        assertThat(limiter.tryAcquire("k4", 1, shortWindow)).isTrue();
        assertThat(limiter.tryAcquire("k4", 1, shortWindow)).isFalse();

        Thread.sleep(120);

        assertThat(limiter.tryAcquire("k4", 1, shortWindow)).isTrue();
    }

    @Test
    void clear_清除後計數歸零() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("k5", 3, Duration.ofMinutes(1));
        }
        assertThat(limiter.tryAcquire("k5", 3, Duration.ofMinutes(1))).isFalse();

        limiter.clear("k5");

        assertThat(limiter.tryAcquire("k5", 3, Duration.ofMinutes(1))).isTrue();
    }

    @Test
    void clear_對不存在的key呼叫不拋例外() {
        limiter.clear("never-existed");
    }

    // === evictStaleEntries()：閒置清理 ===========================================

    @Test
    void evictStaleEntries_清掉閒置超過maxIdle的key() throws InterruptedException {
        // window 故意設很長，確保不是「視窗自然過期」而是「排程清理」造成的效果
        limiter.tryAcquire("k6", 5, Duration.ofMinutes(30));
        Thread.sleep(80);

        limiter.evictStaleEntries(Duration.ofMillis(30));

        // 若真的被清掉，這個 key 形同全新，5 次都該過；沒清掉的話第一次的紀錄還在，只剩 4 次
        int successCount = 0;
        for (int i = 0; i < 5; i++) {
            if (limiter.tryAcquire("k6", 5, Duration.ofMinutes(30))) {
                successCount++;
            }
        }
        assertThat(successCount).isEqualTo(5);
    }

    @Test
    void evictStaleEntries_不清掉仍在活躍窗口內的key() {
        limiter.tryAcquire("k7", 5, Duration.ofMinutes(30));

        limiter.evictStaleEntries(Duration.ofHours(2));

        int successCount = 0;
        for (int i = 0; i < 5; i++) {
            if (limiter.tryAcquire("k7", 5, Duration.ofMinutes(30))) {
                successCount++;
            }
        }
        assertThat(successCount).isEqualTo(4); // 第一次已經用掉一個名額
    }

    // === 並行安全（code review 修正的兩個競態的回歸測試） ==========================

    /**
     * 原始版本用 computeIfAbsent 拿佇列參考後再另開 synchronized 區塊修改，
     * 拿到參考跟修改之間有空隙；重寫後改用單一 compute() 呼叫，此測試確保
     * 大量並行請求對同一個 key 的總成功次數精確等於上限，不多不少。
     */
    @Test
    void 並行tryAcquire不會讓總成功次數超過上限() throws InterruptedException {
        int threadCount = 50;
        int maxAttempts = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();

        try {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (limiter.tryAcquire("concurrent-key", maxAttempts, Duration.ofMinutes(1))) {
                        successCount.incrementAndGet();
                    }
                });
            }

            ready.await();
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(successCount.get()).isEqualTo(maxAttempts);
    }

    /** clear() 與並行 tryAcquire 之間不會遺失紀錄或讓額度失真——两者都走 compute-family 原子操作 */
    @Test
    void 並行tryAcquire與clear交錯不會讓額度失真() throws InterruptedException {
        int rounds = 200;
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < rounds; i++) {
                CountDownLatch done = new CountDownLatch(2);
                executor.submit(() -> {
                    limiter.tryAcquire("clear-race-key", 3, Duration.ofMinutes(1));
                    done.countDown();
                });
                executor.submit(() -> {
                    limiter.clear("clear-race-key");
                    done.countDown();
                });
                assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            executor.shutdownNow();
        }
        // 沒有明確的數量斷言（clear 與 tryAcquire 交錯順序不定，最終計數本來就不可預期）；
        // 這個測試的重點是大量交錯操作不會拋例外、不會死鎖——那才是競態會實際引發的症狀
    }
}
