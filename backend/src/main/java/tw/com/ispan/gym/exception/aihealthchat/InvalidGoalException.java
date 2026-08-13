package tw.com.ispan.gym.exception.aihealthchat;

import org.springframework.http.HttpStatus;

import tw.com.ispan.gym.exception.ApiException;

public class InvalidGoalException extends ApiException {
    public InvalidGoalException(String goal) {
        super(HttpStatus.BAD_REQUEST, "INVALID_GOAL", "不支援的訓練目標：" + goal);
    }
}
