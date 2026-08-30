package tw.com.ispan.gym.controller.employee;


import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.service.file.FileService;
import tw.com.ispan.gym.dto.file.UploadResult;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/files")
@Slf4j
@PreAuthorize("hasAnyRole('ADMIN', 'EMPLOYEE')")
public class AdminFileController {

    private final FileService fileService;

    /**
     * 後台共用圖片上傳端點
     */
    @PostMapping("/upload")
    public ResponseEntity<UploadResult> uploadImage(@RequestParam("file") MultipartFile file) {
        log.info("Request to upload image: name={}", file.getOriginalFilename());
        UploadResult result = fileService.saveImage(file);
        return ResponseEntity.ok(result);
    }
}
