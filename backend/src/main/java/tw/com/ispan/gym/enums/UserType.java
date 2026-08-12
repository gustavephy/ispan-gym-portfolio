package tw.com.ispan.gym.enums;

/**
 * 登入身分所屬的 id 空間。
 *
 * member 與 employee 是兩張獨立的表，id 各自從 1 開始，
 * 因此 JWT 的 subject（一個數字）必須搭配本欄位才知道要查哪張表——
 * 少了它，employee_id=5 的 token 會被誤認成 member_id=5 的會員。
 */
public enum UserType {
	MEMBER, EMPLOYEE
}
