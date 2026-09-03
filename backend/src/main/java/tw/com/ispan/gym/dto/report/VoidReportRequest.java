package tw.com.ispan.gym.dto.report;

import jakarta.validation.constraints.Size;

/** 作廢報表快照請求。原因選填（最多 200 字），留空代表未說明；整個 body 省略也可以 */
public record VoidReportRequest(@Size(max = 200) String reason) {
}
