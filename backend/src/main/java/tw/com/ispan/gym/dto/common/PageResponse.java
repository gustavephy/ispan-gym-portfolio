package tw.com.ispan.gym.dto.common;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 分頁查詢的共用回傳包裝，給所有分頁端點共用（不綁定特定模組），
 * 所以放在 dto.common 而不是任何一個模組的 dto 子套件底下。
 */
@Data
@AllArgsConstructor
public class PageResponse<T> {
    private List<T> items;

    /** 目前頁碼，從 0 開始 */
    private int page;

    private int size;
    private long totalItems;
    private int totalPages;
}
