package tw.com.ispan.gym.exception.aihealthchat;

import org.springframework.http.HttpStatus;

import tw.com.ispan.gym.exception.ApiException;

public class AiHealthPlanNotFoundException extends ApiException {
    public AiHealthPlanNotFoundException(Integer id) {
        super(HttpStatus.NOT_FOUND, "AI_HEALTH_PLAN_NOT_FOUND", "找不到課表：" + id);
    }
}
