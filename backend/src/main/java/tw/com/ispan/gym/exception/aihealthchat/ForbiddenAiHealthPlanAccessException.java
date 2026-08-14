package tw.com.ispan.gym.exception.aihealthchat;

import org.springframework.http.HttpStatus;

import tw.com.ispan.gym.exception.ApiException;

public class ForbiddenAiHealthPlanAccessException extends ApiException {
    public ForbiddenAiHealthPlanAccessException(Integer id) {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN_AI_HEALTH_PLAN_ACCESS", "無權存取此課表：" + id);
    }
}
