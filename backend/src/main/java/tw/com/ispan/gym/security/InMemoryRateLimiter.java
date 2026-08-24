package tw.com.ispan.gym.security;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 單機記憶體內的滑動視窗計數器，密碼管理的忘記密碼限流（email／IP）與修改密碼防暴力猜測
 * 共用同一套元件，各自帶不同的 key 前綴與門檻呼叫。刻意不做成叢集安全——這個專案是單機
 * 部署（見 [[password_management_phase_scope]]），引入 Redis 等外部儲存對目前規模是過度工程。
 *
 * <p>每個 key 對應一個時間戳佇列。所有會讀取或修改某個 key 的方法都透過
 * {@code ConcurrentHashMap} 的 compute 家族方法（{@code compute}/{@code computeIfPresent}）
 * 執行，讓「取得目前佇列→判斷→修改」整個序列對同一個 key 是原子的一步，不會被另一個
 * 執行緒的 {@code clear()}／{@code evictStaleEntries()} 插進中間。
 *
 * <p>（code review 修正，2026-08-21）最早的版本是 {@code computeIfAbsent} 拿到佇列參考後，
 * 再用另一個獨立的 {@code synchronized(佇列)} 區塊修改它——這個參考「拿到」跟「修改」
 * 中間有空隙，若剛好在空隙裡佇列被 {@code clear()} 從 map 移除，這次修改就會寫進一個
 * 已經脫離 map 的物件，紀錄憑空消失。現在全部改成單一 compute 呼叫內完成，不再需要
 * 額外的 synchronized 區塊，因為 compute 家族方法對同一個 key 本來就互斥。
 */
@Component
public class InMemoryRateLimiter {

    private final ConcurrentHashMap<String, Deque<Instant>> buckets = new ConcurrentHashMap<>();

    /**
     * 檢查並立即占用一個名額（原子操作，檢查與記錄不會被並行請求穿透）。
     * 額度內回 true 且已計入這次呼叫；額度已滿回 false，不計入。
     */
    public boolean tryAcquire(String key, int maxAttempts, Duration window) {
        boolean[] acquired = new boolean[1];
        buckets.compute(key, (k, existing) -> {
            Deque<Instant> hits = existing != null ? existing : new ArrayDeque<>();
            prune(hits, window);
            if (hits.size() >= maxAttempts) {
                acquired[0] = false;
            } else {
                hits.addLast(Instant.now());
                acquired[0] = true;
            }
            return hits;
        });
        return acquired[0];
    }

    /** 清除某個 key 的紀錄（例如修改密碼成功後，清掉該帳號的失敗計數） */
    public void clear(String key) {
        buckets.remove(key);
    }

    /**
     * 定期清理已經沒有任何有效紀錄的 key，避免 map 隨著時間無限增長。
     * 由排程呼叫；maxIdle 應大於任何單一呼叫方用到的最長視窗，確保不會清掉還在計算中的資料。
     */
    public void evictStaleEntries(Duration maxIdle) {
        Instant cutoff = Instant.now().minus(maxIdle);
        for (String key : buckets.keySet()) {
            buckets.computeIfPresent(key, (k, hits) -> (hits.isEmpty() || hits.peekLast().isBefore(cutoff)) ? null : hits);
        }
    }

    /** 只能在已經持有該 key 的 compute-family 鎖時呼叫；不獨立加鎖 */
    private void prune(Deque<Instant> hits, Duration window) {
        Instant cutoff = Instant.now().minus(window);
        while (!hits.isEmpty() && hits.peekFirst().isBefore(cutoff)) {
            hits.pollFirst();
        }
    }
}
