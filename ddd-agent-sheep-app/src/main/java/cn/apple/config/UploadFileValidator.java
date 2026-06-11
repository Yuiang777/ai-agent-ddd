package cn.apple.config;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.Locale;

@Component
public class UploadFileValidator {

    private final UploadProperties properties;

    public UploadFileValidator(UploadProperties properties) {
        this.properties = properties;
    }

    public String validateAndExtension(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (file.getSize() > properties.getMaxFileSize().toBytes()) {
            throw new IllegalArgumentException("上传文件不能超过 " + properties.getMaxFileSize());
        }

        String original = file.getOriginalFilename() == null ? "" : Path.of(file.getOriginalFilename()).getFileName().toString();
        int idx = original.lastIndexOf('.');
        if (idx < 0 || idx == original.length() - 1) {
            throw new IllegalArgumentException("上传文件扩展名不合法");
        }

        String extension = original.substring(idx + 1).toLowerCase(Locale.ROOT);
        if (!properties.getAllowedExtensions().contains(extension)) {
            throw new IllegalArgumentException("不支持的上传文件类型");
        }

        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!contentType.isBlank() && !properties.getAllowedContentTypes().contains(contentType)) {
            throw new IllegalArgumentException("不支持的上传文件内容类型");
        }
        return "." + extension;
    }
}
