package tw.com.ispan.gym.dto.file;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UploadResult(
    String url,
    @JsonProperty("isDuplicate") boolean isDuplicate
) {}
