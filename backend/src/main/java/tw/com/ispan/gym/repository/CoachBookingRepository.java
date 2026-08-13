package tw.com.ispan.gym.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.reservation.CoachBooking;

public interface CoachBookingRepository extends JpaRepository<CoachBooking, Integer> {
    /** 查詢某會員的所有預約紀錄，依建立時間新到舊排序，供會員查看自己的預約歷史。 */
    List<CoachBooking> findByMemberIdOrderByCreatedAtDesc(Integer memberId);
}