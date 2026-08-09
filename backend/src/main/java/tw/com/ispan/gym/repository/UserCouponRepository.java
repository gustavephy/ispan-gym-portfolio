package tw.com.ispan.gym.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.UserCoupon;

public interface UserCouponRepository extends JpaRepository<UserCoupon, Integer> {
//	List<UserCoupon> findByUserId(Integer userId);
}
