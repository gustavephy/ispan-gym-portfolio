package tw.com.ispan.gym.controller.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tw.com.ispan.gym.dto.system.SystemMetricsResponse;
import tw.com.ispan.gym.service.system.SystemMonitorService;

/** 系統監控。掛在 /api/admin 下，由 SecurityConfig 的 hasRole("ADMIN") 統一把關。 */
@RestController
@RequestMapping("/api/admin/system")
public class SystemMonitorController {

    private final SystemMonitorService systemMonitorService;

    public SystemMonitorController(SystemMonitorService systemMonitorService) {
        this.systemMonitorService = systemMonitorService;
    }

    /** 即時系統指標；前端每數秒輪詢一次 */
    @GetMapping("/metrics")
    public SystemMetricsResponse metrics() {
        return systemMonitorService.currentMetrics();
    }
}
