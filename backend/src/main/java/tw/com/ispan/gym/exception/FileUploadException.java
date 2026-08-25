package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

public class FileUploadException extends ApiException {
    public FileUploadException(String message) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_UPLOAD_FAILED", message);
    }
}
