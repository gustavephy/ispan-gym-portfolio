package tw.com.ispan.gym;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import jakarta.transaction.Transactional;
import tw.com.ispan.gym.dto.product.ProductResponse;
import tw.com.ispan.gym.entity.product.Product;
import tw.com.ispan.gym.repository.product.ProductRepository;

@SpringBootTest
class BackendApplicationTests {

	@Autowired
	private ProductRepository productRepository;

	@Test
	void contextLoads() {
	}

	@Test
	@Transactional
	void testEntityDtoRepoCorrectness() {
		System.out.println("------ Running testEntityDtoRepoCorrectness ------");
		System.out.println("1. 測試 Repository 查詢商品列表");
		List<Product> products = productRepository.findAll();
		System.out.println(">>> 查詢成功！目前資料庫中共有 " + products.size() + " 筆商品。<<<");
		if (!products.isEmpty()) {
			System.out.println("2. 測試 Entity 轉換為 ProductResponse DTO");
			Product firstProduct = products.get(0);
			ProductResponse dto = ProductResponse.from(firstProduct);
			System.out.println("DTO 轉換成功！商品名稱為：" + dto.name() + "，狀態為：" + dto.status());
		} else {
			System.out.println("警告：目前資料庫中沒有商品資料，請確認資料庫中是否有 seeds 資料。");
		}
		System.out.println("=========================================");
	}
}
