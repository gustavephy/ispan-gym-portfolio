package tw.com.ispan.gym.exception.aihealthchat;

import org.springframework.http.HttpStatus;

import tw.com.ispan.gym.exception.ApiException;

public class DuplicateInbodyRecordException extends ApiException {
    public DuplicateInbodyRecordException() {
        super(HttpStatus.CONFLICT, "DUPLICATE_INBODY_RECORD", "同一天已經有一筆 InBody 紀錄");
    }
}
