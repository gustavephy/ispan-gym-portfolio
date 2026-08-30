package tw.com.ispan.gym.config;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import tw.com.ispan.gym.entity.course.GroupCourse;
import tw.com.ispan.gym.entity.course.PrivateCourse;
import tw.com.ispan.gym.entity.product.Product;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.repository.course.GroupCourseRepository;
import tw.com.ispan.gym.repository.course.PrivateCourseRepository;
import tw.com.ispan.gym.repository.product.ProductRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

@Component
public class CloudinaryMigrationRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CloudinaryMigrationRunner.class);

    private final ProductRepository productRepository;
    private final GroupCourseRepository groupCourseRepository;
    private final PrivateCourseRepository privateCourseRepository;
    private final TrainerRepository trainerRepository;
    private final Cloudinary cloudinary;

    /** 未設定 CLOUDINARY_* 時為空字串——此時整個遷移直接略過，圖片維持本地 /uploads/ 提供 */
    @org.springframework.beans.factory.annotation.Value("${app.cloudinary.cloud-name:}")
    private String cloudName;

    public CloudinaryMigrationRunner(
            ProductRepository productRepository,
            GroupCourseRepository groupCourseRepository,
            PrivateCourseRepository privateCourseRepository,
            TrainerRepository trainerRepository,
            Cloudinary cloudinary) {
        this.productRepository = productRepository;
        this.groupCourseRepository = groupCourseRepository;
        this.privateCourseRepository = privateCourseRepository;
        this.trainerRepository = trainerRepository;
        this.cloudinary = cloudinary;
    }

    @Override
    public void run(String... args) throws Exception {
        if (cloudName == null || cloudName.isBlank()) {
            log.info("未設定 Cloudinary 金鑰（CLOUDINARY_*），略過雲端圖片遷移；圖片維持本地 /uploads/ 提供");
            return;
        }
        log.info("Starting Cloudinary migration check for existing local images...");

        // 1. Migrate Products
        List<Product> products = productRepository.findAll();
        for (Product p : products) {
            String url = p.getImageUrl();
            if (url != null && url.startsWith("/uploads/")) {
                String cloudUrl = migrateFile(url);
                if (cloudUrl != null) {
                    p.setImageUrl(cloudUrl);
                    productRepository.save(p);
                    log.info("Migrated product id {} to Cloudinary: {}", p.getProductId(), cloudUrl);
                }
            }
        }

        // 2. Migrate GroupCourses
        List<GroupCourse> groupCourses = groupCourseRepository.findAll();
        for (GroupCourse gc : groupCourses) {
            String url = gc.getImageUrl();
            if (url != null && url.startsWith("/uploads/")) {
                String cloudUrl = migrateFile(url);
                if (cloudUrl != null) {
                    gc.setImageUrl(cloudUrl);
                    groupCourseRepository.save(gc);
                    log.info("Migrated group course id {} to Cloudinary: {}", gc.getId(), cloudUrl);
                }
            }
        }

        // 3. Migrate PrivateCourses
        List<PrivateCourse> privateCourses = privateCourseRepository.findAll();
        for (PrivateCourse pc : privateCourses) {
            String url = pc.getImageUrl();
            if (url != null && url.startsWith("/uploads/")) {
                String cloudUrl = migrateFile(url);
                if (cloudUrl != null) {
                    pc.setImageUrl(cloudUrl);
                    privateCourseRepository.save(pc);
                    log.info("Migrated private course id {} to Cloudinary: {}", pc.getPrivateCourseId(), cloudUrl);
                }
            }
        }

        // 4. Migrate Trainers
        List<Trainer> trainers = trainerRepository.findAll();
        for (Trainer t : trainers) {
            String url = t.getProfileImageUrl();
            if (url != null && url.startsWith("/uploads/")) {
                String cloudUrl = migrateFile(url);
                if (cloudUrl != null) {
                    t.setProfileImageUrl(cloudUrl);
                    trainerRepository.save(t);
                    log.info("Migrated trainer id {} to Cloudinary: {}", t.getId(), cloudUrl);
                }
            }
        }

        log.info("Cloudinary migration check finished.");
    }

    private String migrateFile(String relativeUrl) {
        try {
            // relativeUrl looks like "/uploads/xxx.jpg"
            String filename = relativeUrl.replace("/uploads/", "");
            
            // Check in root uploads or backend uploads
            File file = new File("uploads", filename);
            if (!file.exists()) {
                file = new File("../uploads", filename);
            }
            if (!file.exists()) {
                log.warn("Local file not found for migration: {}", relativeUrl);
                return null;
            }

            byte[] fileBytes = Files.readAllBytes(file.toPath());
            
            // Compute MD5
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(fileBytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            String publicId = sb.toString();

            // Check if already on Cloudinary
            try {
                Map<?, ?> resource = cloudinary.api().resource(publicId, ObjectUtils.emptyMap());
                if (resource != null && resource.containsKey("secure_url")) {
                    return (String) resource.get("secure_url");
                }
            } catch (com.cloudinary.api.exceptions.NotFound e) {
                // Not found, we will upload it
            }

            // Upload
            Map<?, ?> uploadResult = cloudinary.uploader().upload(fileBytes, ObjectUtils.asMap(
                    "public_id", publicId,
                    "overwrite", true
            ));
            return (String) uploadResult.get("secure_url");
        } catch (Exception e) {
            log.error("Failed to migrate file: {}", relativeUrl, e);
            return null;
        }
    }
}
