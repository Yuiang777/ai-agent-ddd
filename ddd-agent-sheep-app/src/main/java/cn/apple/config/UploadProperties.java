package cn.apple.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.util.Set;

@ConfigurationProperties(prefix = "app.upload")
public class UploadProperties {

    private DataSize maxFileSize = DataSize.ofMegabytes(20);
    private Set<String> allowedExtensions = Set.of("pdf", "doc", "docx", "txt", "md", "png", "jpg", "jpeg", "webp", "mp4", "mov", "avi");
    private Set<String> allowedContentTypes = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
            "text/markdown",
            "image/png",
            "image/jpeg",
            "image/webp",
            "video/mp4",
            "video/quicktime",
            "video/x-msvideo"
    );

    public DataSize getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(DataSize maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public Set<String> getAllowedExtensions() {
        return allowedExtensions;
    }

    public void setAllowedExtensions(Set<String> allowedExtensions) {
        this.allowedExtensions = allowedExtensions;
    }

    public Set<String> getAllowedContentTypes() {
        return allowedContentTypes;
    }

    public void setAllowedContentTypes(Set<String> allowedContentTypes) {
        this.allowedContentTypes = allowedContentTypes;
    }
}
