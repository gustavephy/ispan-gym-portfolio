package tw.com.ispan.gym.exception.aihealthchat;

import org.springframework.http.HttpStatus;

import tw.com.ispan.gym.exception.ApiException;

public class MissingInbodyMeasurementException extends ApiException {
    public MissingInbodyMeasurementException() {
        super(HttpStatus.BAD_REQUEST, "MISSING_INBODY_MEASUREMENT", "至少要填寫一項 InBody 量測值");
    }
}
