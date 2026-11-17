package cn.apple.config;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class UploadFileValidator {

    private static final int HEADER_BYTES = 16;
    private static final int TEXT_SAMPLE_BYTES = 8192;
    private static final int MAX_DOCX_ENTRIES = 512;
    private static final Map<String, Set<String>> CONTENT_TYPES_BY_EXTENSION = Map.ofEntries(
            Map.entry("pdf", Set.of("application/pdf")),
            Map.entry("doc", Set.of("application/msword")),
            Map.entry("docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
            Map.entry("txt", Set.of("text/plain")),
            Map.entry("md", Set.of("text/plain", "text/markdown")),
            Map.entry("png", Set.of("image/png")),
            Map.entry("jpg", Set.of("image/jpeg")),
            Map.entry("jpeg", Set.of("image/jpeg")),
            Map.entry("webp", Set.of("image/webp")),
            Map.entry("mp4", Set.of("video/mp4")),
            Map.entry("mov", Set.of("video/quicktime")),
            Map.entry("avi", Set.of("video/x-msvideo"))
    );

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

        String contentType = file.getContentType() == null ? ""
                : file.getContentType().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!contentType.isBlank() && !properties.getAllowedContentTypes().contains(contentType)) {
            throw new IllegalArgumentException("不支持的上传文件内容类型");
        }
        Set<String> expectedContentTypes = CONTENT_TYPES_BY_EXTENSION.get(extension);
        if (expectedContentTypes == null) {
            throw new IllegalArgumentException("上传文件类型尚未配置内容校验规则");
        }
        if (!contentType.isBlank() && !expectedContentTypes.contains(contentType)) {
            throw new IllegalArgumentException("文件扩展名与声明的内容类型不一致");
        }
        validateContent(file, extension);
        return "." + extension;
    }

    private void validateContent(MultipartFile file, String extension) {
        try {
            byte[] header;
            try (InputStream input = new BufferedInputStream(file.getInputStream())) {
                header = input.readNBytes(HEADER_BYTES);
            }
            boolean valid = switch (extension) {
                case "pdf" -> startsWith(header, ascii("%PDF-"));
                case "png" -> startsWith(header, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
                case "jpg", "jpeg" -> startsWith(header, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});
                case "webp" -> matchesAt(header, 0, ascii("RIFF")) && matchesAt(header, 8, ascii("WEBP"));
                case "doc" -> startsWith(header, new byte[]{(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0,
                        (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1});
                case "docx" -> startsWith(header, new byte[]{0x50, 0x4b, 0x03, 0x04}) && isWordOpenXml(file);
                case "txt", "md" -> isText(file);
                case "mp4", "mov" -> matchesAt(header, 4, ascii("ftyp"));
                case "avi" -> matchesAt(header, 0, ascii("RIFF")) && matchesAt(header, 8, ascii("AVI "));
                default -> false;
            };
            if (!valid) {
                throw new IllegalArgumentException("文件内容与扩展名不一致或文件结构无效");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取上传文件进行内容校验", e);
        }
    }

    private boolean isWordOpenXml(MultipartFile file) throws IOException {
        boolean contentTypes = false;
        boolean wordContent = false;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(file.getInputStream()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null && entries++ < MAX_DOCX_ENTRIES) {
                String name = entry.getName().replace('\\', '/');
                contentTypes |= "[Content_Types].xml".equals(name);
                wordContent |= name.startsWith("word/") && !entry.isDirectory();
                if (contentTypes && wordContent) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isText(MultipartFile file) throws IOException {
        byte[] sample;
        try (InputStream input = new BufferedInputStream(file.getInputStream())) {
            sample = input.readNBytes(TEXT_SAMPLE_BYTES);
        }
        int suspiciousControls = 0;
        for (byte current : sample) {
            int value = Byte.toUnsignedInt(current);
            if (value == 0) {
                return false;
            }
            if (value < 0x20 && value != '\t' && value != '\n' && value != '\r' && value != '\f') {
                suspiciousControls++;
            }
        }
        return sample.length == 0 || suspiciousControls * 10 <= sample.length;
    }

    private boolean startsWith(byte[] value, byte[] signature) {
        return value.length >= signature.length
                && Arrays.equals(Arrays.copyOf(value, signature.length), signature);
    }

    private boolean matchesAt(byte[] value, int offset, byte[] signature) {
        if (offset < 0 || value.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (value[offset + i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private byte[] ascii(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
}
