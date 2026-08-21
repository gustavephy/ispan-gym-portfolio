package tw.com.ispan.gym.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 純單元測試，不需要 ConstraintValidatorContext（實作完全沒用到它，傳 null 即可）。
 * 規則：8–72 字元、含大小寫英文字母與數字、不可有任何空白，且 UTF-8 編碼後不得超過
 * 72 bytes——最後一條是這次 code review 特別補的，字元數規則本身看不出字元集合，
 * 多位元字元湊字數時可能字元數合法但 byte 數已經超過 BCrypt 的有效輸入長度。
 */
class PasswordValidatorTest {

    private final PasswordValidator validator = new PasswordValidator();

    private boolean valid(String password) {
        return validator.isValid(password, null);
    }

    @Test
    void null值視為通過_必填交給NotBlank負責() {
        assertThat(valid(null)).isTrue();
    }

    @Test
    void 符合所有規則的密碼通過() {
        assertThat(valid("Admin@12345")).isTrue();
    }

    @Test
    void 不要求特殊符號但允許存在() {
        assertThat(valid("Abcdefg1")).isTrue();
        assertThat(valid("Abcdefg1!@#")).isTrue();
    }

    // === 長度邊界 =================================================================

    @Test
    void 剛好8個字元通過() {
        assertThat(valid("Aa123456")).isTrue();
    }

    @Test
    void 少於8個字元不通過() {
        assertThat(valid("Aa12345")).isFalse();
    }

    @Test
    void 剛好72個字元通過() {
        String password = "Aa1" + "b".repeat(69); // 3 + 69 = 72
        assertThat(password).hasSize(72);
        assertThat(valid(password)).isTrue();
    }

    @Test
    void 超過72個字元不通過() {
        String password = "Aa1" + "b".repeat(70); // 73 個字元
        assertThat(password).hasSize(73);
        assertThat(valid(password)).isFalse();
    }

    // === 字元類型規則 =============================================================

    @Test
    void 缺少大寫字母不通過() {
        assertThat(valid("admin@12345")).isFalse();
    }

    @Test
    void 缺少小寫字母不通過() {
        assertThat(valid("ADMIN@12345")).isFalse();
    }

    @Test
    void 缺少數字不通過() {
        assertThat(valid("AdminPassword")).isFalse();
    }

    @Test
    void 開頭空白不通過() {
        assertThat(valid(" Admin@12345")).isFalse();
    }

    @Test
    void 結尾空白不通過() {
        assertThat(valid("Admin@12345 ")).isFalse();
    }

    @Test
    void 中間夾空白不通過() {
        assertThat(valid("Admin @12345")).isFalse();
    }

    @Test
    void 全部都是空白不通過() {
        assertThat(valid("                                        ")).isFalse();
    }

    // === UTF-8 byte 長度限制（這次 code review 補的規則）==========================

    /**
     * 24 個中文字（每個 3 bytes）+ "Aa1" 三個 ASCII 字元：
     * 字元數 = 3 + 24 = 27（遠低於 72 字元上限，能通過長度 regex），
     * 但 UTF-8 byte 數 = 3 + 24*3 = 75，超過 BCrypt 的 72 bytes 有效輸入上限——
     * 這正是只看字元數規則會漏掉、必須另外用 byte 長度擋下的情境。
     */
    @Test
    void 字元數合法但UTF8byte數超過72時不通過() {
        String password = "Aa1" + "測".repeat(24);
        assertThat(password).hasSizeLessThanOrEqualTo(72);
        assertThat(password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(72);

        assertThat(valid(password)).isFalse();
    }

    @Test
    void 字元數與byte數都在上限內時通過() {
        // 8 個中文字 + Aa1：字元數 11、byte 數 3 + 8*3 = 27，兩者都遠低於上限
        String password = "Aa1" + "測".repeat(8);
        assertThat(valid(password)).isTrue();
    }
}
