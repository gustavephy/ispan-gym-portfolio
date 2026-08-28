package tw.com.ispan.gym.controller.employee;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.service.file.FileService;

/**
 * 員工（含教練）自助上傳圖片，目前只給員工個資頁的教練頭像用。
 *
 * <p>為什麼不沿用 {@code POST /api/admin/products/upload}：那支掛在 AdminProductController 下，
 * 整個 controller 是 {@code hasRole('ADMIN')}，一般教練（EMPLOYEE＋TRAINER、非管理員）打過去
 * 一律 403，「教練自己換頭像」這個功能對它的目標使用者根本不能用。儲存邏輯共用
 * {@link FileService}（白名單、去重都在那裡），只是門檻改成在職員工。
 */
@Slf4j
@RestController
@RequestMapping("/api/employee/uploads")
@PreAuthorize("hasRole('EMPLOYEE')")
public class EmployeeUploadController {

    private final FileService fileService;

    public EmployeeUploadController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/images")
    public ResponseEntity<Map<String, String>> uploadImage(@RequestParam("file") MultipartFile file) {
        log.info("Employee image upload: name={}", file.getOriginalFilename());
        return ResponseEntity.ok(Map.of("url", fileService.saveImage(file)));
    }
}
