package tw.com.ispan.gym.config;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.enums.account.AccountType;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;

/**
 * 種子管理員：員工帳號要靠既有員工審核才能從 PENDING_APPROVAL 轉 ACTIVE，
 * 但第一個員工沒有人可以審核它——啟動時這組 email 還不存在就自動建立，
 * 直接是 ACTIVE、role=ADMIN，reviewed_by／reviewed_at 維持 NULL（始祖帳號沒有審核人）。
 *
 * <p>預設關閉（{@code app.seed.admin-enabled=false}），只在需要種出始祖帳號時手動開啟，
 * 且沒給帳密時直接啟動失敗，不會安靜略過——避免「開了開關卻沒設帳密」導致系統從頭到尾
 * 沒有始祖帳號、卻毫無警訊。
 *
 * <p>跟 {@link SchemaFixupRunner} 一樣冪等（用固定 email 判斷是否已建立過，且會驗證
 * 既有帳號真的可用，不一致就報錯而不是安靜略過或自動升權），
 * {@code @Order} 排在它後面，確保 schema 補完才寫入資料。
 */
@Component
@Order(1)
public class AdminSeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeedRunner.class);

    private final AccountRepository accounts;
    private final EmployeeRepository employees;
    private final PasswordEncoder passwordEncoder;
    private final boolean seedEnabled;
    private final String seedEmail;
    private final String seedPassword;

    AdminSeedRunner(
            AccountRepository accounts,
            EmployeeRepository employees,
            PasswordEncoder passwordEncoder,
            @Value("${app.seed.admin-enabled}") boolean seedEnabled,
            @Value("${app.seed.admin-email}") String seedEmail,
            @Value("${app.seed.admin-password}") String seedPassword) {
        this.accounts = accounts;
        this.employees = employees;
        this.passwordEncoder = passwordEncoder;
        this.seedEnabled = seedEnabled;
        this.seedEmail = seedEmail;
        this.seedPassword = seedPassword;
    }

    // 兩個 save() 綁成同一交易：進程若在兩者之間被中止，DB 端會整個回滾，
    // 不會留下「有 Account、沒 Employee」的孤兒種子帳號；下次啟動會乾淨地重新播種。
    @Override
    @Transactional
    public void run(String... args) {
        if (!seedEnabled) {
            log.info("種子管理員功能未啟用（app.seed.admin-enabled=false），略過");
            return;
        }
        // 明確啟用卻沒給帳密：這代表操作者的意圖跟實際設定衝突，不能安靜略過——
        // 那樣會變成「開了種子功能但沒有種子帳號」，之後沒有人能核准第一位員工，
        // 而且不會有任何啟動期的警訊，只會在有人第一次嘗試審核時才發現系統沒有始祖帳號。
        // 直接讓啟動失敗，逼操作者當下就處理。
        if (seedEmail.isBlank() || seedPassword.isBlank()) {
            throw new IllegalStateException(
                    "已啟用種子管理員（app.seed.admin-enabled=true），"
                            + "但尚未設定 app.seed.admin-email／app.seed.admin-password");
        }

        Account existing = accounts.findByEmailIgnoreCase(seedEmail).orElse(null);
        if (existing != null) {
            // 這個 email 之前已經有帳號不代表種子一定成功過（可能是別的流程碰巧用了同個 email、
            // 或早期一次失敗的部分寫入）。驗證它真的是一個可用的始祖帳號，不只看 Employee，
            // Account 本身也要可用——不一致就報錯停止，絕不自動把它「修好」變成 ADMIN。
            Employee employee = employees.findByAccountId(existing.getId()).orElse(null);
            boolean valid = existing.getDeletedAt() == null
                    && existing.getPasswordHash() != null
                    && existing.getRoles().contains(AccountType.EMPLOYEE)
                    && employee != null
                    && employee.getDeletedAt() == null
                    && employee.getStatus() == EmployeeStatus.ACTIVE
                    && employee.getRole() == EmployeeRole.ADMIN;
            if (!valid) {
                throw new IllegalStateException(
                        "種子管理員 email（" + seedEmail + "）已有對應帳號，"
                                + "但沒有有效的 ACTIVE／ADMIN／未刪除員工資料，請人工確認——不會自動升級此帳號權限");
            }
            return; // 已存在且狀態正確，冪等略過
        }

        Account account = new Account();
        account.setEmail(seedEmail);
        account.setPasswordHash(passwordEncoder.encode(seedPassword));
        account.getRoles().add(AccountType.EMPLOYEE);
        account = accounts.save(account);

        Employee founder = new Employee();
        founder.setAccount(account);
        founder.setFirstName("System");
        founder.setLastName("Admin");
        founder.setPhone("0900000000");
        founder.setRole(EmployeeRole.ADMIN);
        founder.setStatus(EmployeeStatus.ACTIVE);
        founder.setHireDate(LocalDate.now());
        employees.save(founder);

        log.info("已建立種子管理員帳號，email={}", seedEmail);
    }
}
