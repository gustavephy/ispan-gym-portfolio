package tw.com.ispan.gym.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * 「建立新密碼」共用規則：8–72 字元、需含大小寫英文字母與數字、不可有任何空白，
 * 且 UTF-8 編碼後不得超過 72 bytes（BCrypt 以 byte 計算有效輸入長度，多位元字元
 * 湊到 72 字元時可能已超過 72 bytes 而被靜默截斷）。
 *
 * <p>只套用在「建立新密碼」的欄位（註冊密碼、修改密碼的 newPassword、重設密碼的
 * newPassword）——驗證既有密碼的欄位（登入密碼、修改密碼的 currentPassword、
 * 員工重新申請的密碼）不套用，政策收緊不能回頭讓既有使用者無法證明身分。
 *
 * <p>null 視為通過，交由 {@code @NotBlank} 負責必填檢查，兩者職責分開。
 */
@Documented
@Constraint(validatedBy = PasswordValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {

    String message() default "密碼需為8至72個字元，包含大小寫英文字母及數字，且不可包含空白";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
