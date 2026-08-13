package tw.com.ispan.gym.enums.coupon;

import java.time.LocalDate;

import tw.com.ispan.gym.entity.coupon.Coupon;

public enum CouponQueryStatus {
	ONGOING {
		@Override
		public boolean matches(Coupon c,LocalDate today) {
			return c.getStatus() == CouponStatus.ACTIVE &&
					!c.getStartDate().isAfter(today) &&
					(c.getEndDate() == null || !c.getEndDate().isBefore(today));
		}
	},
	UPCOMING {
		@Override
		public boolean matches(Coupon c, LocalDate today) {
			return c.getStatus() == CouponStatus.ACTIVE &&
					c.getStartDate().isAfter(today);
		}
	},
	EXPIRED {
		@Override
		public boolean matches(Coupon c, LocalDate today) {
			return c.getStatus() == CouponStatus.EXPIRED ||
					c.getStatus() == CouponStatus.INACTIVE ||
					(c.getEndDate() != null && c.getEndDate().isBefore(today));
		}
	},
	ACTIVE {
		@Override
		public boolean matches(Coupon c,LocalDate today) {
			return c.getStatus() == CouponStatus.ACTIVE;
		}
	},
	INACTIVE {
		@Override
		public boolean matches(Coupon c, LocalDate today) {
			return c.getStatus() == CouponStatus.INACTIVE;
		}
	};
	
	public abstract boolean matches(Coupon c, LocalDate today);
	
}
