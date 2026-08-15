package tw.com.ispan.gym.enums.employee;

/** 員工審核／在職狀態：新帳號 PENDING_APPROVAL，待任一 ACTIVE 員工核准後 ACTIVE；拒絕後 REJECTED，可重新申請 */
public enum EmployeeStatus {
	PENDING_APPROVAL, ACTIVE, REJECTED, INACTIVE
}
