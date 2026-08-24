package tw.com.ispan.gym.dto.order;

import jakarta.validation.constraints.Size;

public record RejectRefundRequest(@Size(max = 200) String reason) {}
