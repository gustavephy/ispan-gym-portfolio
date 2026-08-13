package tw.com.ispan.gym.exception.aihealthchat;

import org.springframework.http.HttpStatus;

import tw.com.ispan.gym.exception.ApiException;

public class InbodyRecordNotFoundException extends ApiException {
    public InbodyRecordNotFoundException(Integer id) {
        super(HttpStatus.NOT_FOUND, "INBODY_RECORD_NOT_FOUND", "找不到 InBody 紀錄：" + id);
    }
}
