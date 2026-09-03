package tw.com.ispan.gym.service.coupon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.coupon.Coupon;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.coupon.CouponApplicableType;
import tw.com.ispan.gym.enums.coupon.CouponStatus;
import tw.com.ispan.gym.enums.coupon.CouponType;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.coupon.CouponRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;

@SpringBootTest
public class MemberCouponConcurrencyTest {

    @Autowired
    private MemberCouponService memberCouponService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("高併發搶領優惠券測試：1,000 個不同會員同時搶領限量 10 張的優惠券，確保無超領且資料一致")
    public void testConcurrentCouponClaim() throws InterruptedException {
        // @SpringBootTest 打的是真資料庫，測試自建的資料一律在 finally 清掉——
        // 不清的話每跑一次 mvnw clean test 就永久多 1000 個會員，統計圖表直接失真
        List<Integer> memberIds = new ArrayList<>();
        List<Integer> accountIds = new ArrayList<>();
        try {
            runConcurrentClaim(memberIds, accountIds);
        } finally {
            cleanup(memberIds, accountIds);
        }
    }

    private void runConcurrentClaim(List<Integer> memberIds, List<Integer> accountIds)
            throws InterruptedException {
        // 1. 準備優惠券：限量 10 張
        Coupon coupon = new Coupon();
        coupon.setName("併發測試優惠券");
        coupon.setCode("TEST_CONCURRENT_" + System.currentTimeMillis());
        coupon.setType(CouponType.CASH);
        coupon.setValue(new BigDecimal("100.00"));
        coupon.setMinSpend(new BigDecimal("500.00"));
        coupon.setTotalQuantity(10);
        coupon.setClaimedQuantity(0);
        coupon.setApplicableType(CouponApplicableType.ALL);
        coupon.setStartDate(LocalDate.now().minusDays(1));
        coupon.setEndDate(LocalDate.now().plusDays(10));
        coupon.setStatus(CouponStatus.ACTIVE);
        coupon = couponRepository.save(coupon);
        final Integer couponId = coupon.getCouponId();

        // 2. 準備 1,000 個不同的測試會員（id 記進呼叫端傳入的清單，finally 據此清理）
        int threadCount = 1000;
        for (int i = 0; i < threadCount; i++) {
            Account account = new Account();
            account.setEmail("concurrent_test_" + i + "_" + System.currentTimeMillis() + "@example.com");
            account.setPasswordHash("hashed_password_123");
            account = accountRepository.save(account);
            accountIds.add(account.getId());

            Member member = new Member();
            member.setAccount(account);
            member.setFirstName("Test");
            member.setLastName("Member_" + i);
            member.setPhone("0900000" + String.format("%03d", i));
            member.setStatus(UserStatus.ACTIVE);
            member = memberRepository.save(member);
            memberIds.add(member.getId());
        }

        // 3. 模擬 1,000 個執行緒同時搶領
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final Integer memberId = memberIds.get(i);
            executorService.submit(() -> {
                try {
                    memberCouponService.claimCoupon(memberId, couponId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        // 設 timeout：搶券走 findByIdForUpdate 悲觀鎖，若服務端出現死鎖，
        // 不設上限整個測試套件會永久卡住
        boolean finished = latch.await(60, TimeUnit.SECONDS);
        executorService.shutdown();
        assertTrue(finished, "併發搶領 60 秒內未完成，疑似鎖競爭異常或死鎖");

        // 4. 驗證結果
        Coupon updatedCoupon = couponRepository.findById(couponId).orElseThrow();

        System.out.println("====== 併發測試結果 Summary ======");
        System.out.println("成功領取人數: " + successCount.get());
        System.out.println("失敗（被搶空）人數: " + failCount.get());
        System.out.println("資料庫 Coupon.claimedQuantity: " + updatedCoupon.getClaimedQuantity());
        System.out.println("=================================");

        // 斷言：成功人數必須剛好等於限量 10
        assertEquals(10, successCount.get(), "成功人數必須剛好為 10 人");
        assertEquals(990, failCount.get(), "失敗人數必須剛好為 990 人");
        assertEquals(10, updatedCoupon.getClaimedQuantity(), "庫存已領取數量必須剛好為 10");
    }

    /**
     * 依 FK 順序清掉本測試自建的資料：領取 → 券 → 會員 → 帳號；斷言失敗也照清（finally 呼叫）。
     * 券以 code 前綴辨識而非 id——測試中途拋出時呼叫端拿不到 id，前綴連歷史殘留一併撈得到。
     */
    private void cleanup(List<Integer> memberIds, List<Integer> accountIds) {
        jdbc.update("DELETE mc FROM member_coupon mc JOIN coupon c ON c.coupon_id = mc.coupon_id"
                + " WHERE c.code LIKE 'TEST_CONCURRENT_%'");
        jdbc.update("DELETE FROM coupon WHERE code LIKE 'TEST_CONCURRENT_%'");
        if (!memberIds.isEmpty()) {
            jdbc.batchUpdate("DELETE FROM member WHERE member_id = ?",
                    memberIds.stream().map(id -> new Object[] { id }).toList());
        }
        if (!accountIds.isEmpty()) {
            jdbc.batchUpdate("DELETE FROM account WHERE account_id = ?",
                    accountIds.stream().map(id -> new Object[] { id }).toList());
        }
    }
}
