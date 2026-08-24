package tw.com.ispan.gym.repository.account;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.account.PasswordHistory;

public interface PasswordHistoryRepository extends JpaRepository<PasswordHistory, Integer> {

    /** 最近 4 筆，供重用檢查用；同一 createdAt 時以 id 決勝，排序才穩定 */
    List<PasswordHistory> findTop4ByAccountIdOrderByCreatedAtDescIdDesc(Integer accountId);

    /** 寫入新紀錄後裁切用：全部依序取出，服務層自己砍掉第 5 筆之後的 */
    List<PasswordHistory> findByAccountIdOrderByCreatedAtDescIdDesc(Integer accountId);
}
