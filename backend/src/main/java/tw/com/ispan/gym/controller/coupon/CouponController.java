package tw.com.ispan.gym.controller.coupon;


import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.service.coupon.CouponService;


@RestController
@RequiredArgsConstructor
@Slf4j
public class CouponController {
	private final CouponService couponService;
}
