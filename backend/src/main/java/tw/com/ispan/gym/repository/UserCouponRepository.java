package tw.com.ispan.gym.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.MemberCoupon;

public interface UserCouponRepository extends JpaRepository<MemberCoupon, Integer> {
//	List<UserCoupon> findByUserId(Integer userId);
}
