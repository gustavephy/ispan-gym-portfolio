package tw.com.ispan.gym.service.account;

import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.enums.account.VerificationPurpose;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class MailService {

    private final JavaMailSender mailSender;
    private final String verificationBaseUrl;
    private final String passwordResetBaseUrl;

    public MailService(
            JavaMailSender mailSender,
            @Value("${app.mail.verification-base-url}") String verificationBaseUrl,
            @Value("${app.mail.password-reset-base-url}") String passwordResetBaseUrl) {
        this.mailSender = mailSender;
        this.verificationBaseUrl = verificationBaseUrl;
        this.passwordResetBaseUrl = passwordResetBaseUrl;
    }

    public void sendVerificationEmail(String to, UUID token, long ttlMinutes, VerificationPurpose purpose) {
        String link = verificationBaseUrl + "?token=" + token;

        String subject;
        String intro;
        if (purpose == VerificationPurpose.REACTIVATION) {
            subject = "請完成您的帳號重新啟用驗證";
            intro = "您的帳號目前已停用，請點擊以下連結完成驗證以重新啟用帳號";
        } else {
            subject = "請完成您的帳號註冊驗證";
            intro = "感謝您註冊，請點擊以下連結完成驗證";
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject(subject);
        message.setText(intro + "（" + ttlMinutes + " 分鐘內有效）：\n" + link);
        mailSender.send(message);
    }

    /**
     * 密碼重設信。呼叫端（PasswordService）已經在 transaction commit 之後、
     * mailTaskExecutor 的背景執行緒裡才呼叫這個方法，這裡不需要再處理非同步。
     * rawToken 是明文——只有這封信看得到，資料庫只存它的雜湊。
     */
    public void sendPasswordResetEmail(String to, String rawToken, long ttlMinutes) {
        String link = passwordResetBaseUrl + "?token=" + rawToken;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("密碼重設請求");
        message.setText("我們收到您的密碼重設請求，請點擊以下連結設定新密碼（" + ttlMinutes + " 分鐘內有效）：\n" + link
                + "\n\n若您並未提出此請求，請忽略此信，您的密碼不會被變更。");
        mailSender.send(message);
    }
}
