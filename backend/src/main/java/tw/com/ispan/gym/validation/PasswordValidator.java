package tw.com.ispan.gym.validation;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link ValidPassword} 的實作：字元規則用 regex，byte 長度另外算——
 * regex 只看得到字元數，看不到 UTF-8 編碼後的 byte 數。
 */
public class PasswordValidator implements ConstraintValidator<ValidPassword, String> {

    private static final Pattern RULE = Pattern.compile("^(?=\\S+$)(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,72}$");
    private static final int MAX_BYTES = 72;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            // 必填與否交給 @NotBlank，這裡只管格式
            return true;
        }
        if (!RULE.matcher(value).matches()) {
            return false;
        }
        return value.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }
}
