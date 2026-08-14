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

    public MailService(
            JavaMailSender mailSender,
            @Value("${app.mail.verification-base-url}") String verificationBaseUrl) {
        this.mailSender = mailSender;
        this.verificationBaseUrl = verificationBaseUrl;
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
}
