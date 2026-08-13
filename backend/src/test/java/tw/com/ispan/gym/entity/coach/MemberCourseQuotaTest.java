package tw.com.ispan.gym.entity.coach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import tw.com.ispan.gym.exception.BusinessRuleException;

/**
 * MemberCourseQuota 三計數器狀態機的純實體測試（不碰 Spring、不碰 DB）。
 * 驗證的核心是恆等式 total = remaining + reserved + used 在每個事件
 * （book／consume／refund）前後都不變，以及計數器歸零後的防呆。
 * 併發下的 lost update 由 @Version 樂觀鎖把關，那一層需要真實 DB 才能驗證，
 * 不在本測試範圍。
 */
class MemberCourseQuotaTest {

    private MemberCourseQuota quota(int remaining, int reserved, int used) {
        MemberCourseQuota q = new MemberCourseQuota();
        q.setRemainingCourse(remaining);
        q.setReservedCourse(reserved);
        q.setUsedCourse(used);
        return q;
    }

    @Test
    void book_扣remaining加reserved且總數不變() {
        MemberCourseQuota q = quota(2, 0, 0);

        q.book();

        assertThat(q.getRemainingCourse()).isEqualTo(1);
        assertThat(q.getReservedCourse()).isEqualTo(1);
        assertThat(q.getUsedCourse()).isZero();
        assertThat(q.getTotal()).isEqualTo(2);
    }

    @Test
    void book_無剩餘額度擋下() {
        MemberCourseQuota q = quota(0, 1, 1);

        assertThatThrownBy(q::book)
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("額度不足");
        assertThat(q.getTotal()).isEqualTo(2);
    }

    @Test
    void consume_扣reserved加used() {
        MemberCourseQuota q = quota(1, 1, 0);

        q.consume();

        assertThat(q.getRemainingCourse()).isEqualTo(1);
        assertThat(q.getReservedCourse()).isZero();
        assertThat(q.getUsedCourse()).isEqualTo(1);
        assertThat(q.getTotal()).isEqualTo(2);
    }

    @Test
    void consume_無預約中堂數擋下() {
        MemberCourseQuota q = quota(2, 0, 0);

        assertThatThrownBy(q::consume).isInstanceOf(BusinessRuleException.class);
        assertThat(q.getTotal()).isEqualTo(2);
    }

    @Test
    void refund_扣reserved退還remaining() {
        MemberCourseQuota q = quota(0, 1, 0);

        q.refund();

        assertThat(q.getRemainingCourse()).isEqualTo(1);
        assertThat(q.getReservedCourse()).isZero();
        assertThat(q.getUsedCourse()).isZero();
        assertThat(q.getTotal()).isEqualTo(1);
    }

    @Test
    void refund_無預約中堂數擋下() {
        MemberCourseQuota q = quota(2, 0, 0);

        assertThatThrownBy(q::refund).isInstanceOf(BusinessRuleException.class);
        assertThat(q.getTotal()).isEqualTo(2);
    }

    @Test
    void 完整生命週期維持恆等式() {
        MemberCourseQuota q = quota(3, 0, 0);

        q.book();    // 訂課      → (2,1,0)
        q.book();    // 再訂一堂  → (1,2,0)
        q.consume(); // 核銷一堂  → (1,1,1)
        q.refund();  // 準時取消  → (2,0,1)

        assertThat(q.getRemainingCourse()).isEqualTo(2);
        assertThat(q.getReservedCourse()).isZero();
        assertThat(q.getUsedCourse()).isEqualTo(1);
        assertThat(q.getTotal()).isEqualTo(3);
    }
}
