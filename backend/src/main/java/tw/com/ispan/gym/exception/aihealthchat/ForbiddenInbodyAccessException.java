package tw.com.ispan.gym.exception.aihealthchat;

import org.springframework.http.HttpStatus;

import tw.com.ispan.gym.exception.ApiException;

public class ForbiddenInbodyAccessException extends ApiException {
    public ForbiddenInbodyAccessException(Integer id) {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN_INBODY_ACCESS", "無權存取此 InBody 紀錄：" + id);
    }
}
