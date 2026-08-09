package tw.com.ispan.gym.dto.coupon;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CouponStatsResponse {
	private Integer activeCount;
	private Integer upcomingCount;
	private Integer expiredCount;
	private Integer totalClaimedCount;
}
