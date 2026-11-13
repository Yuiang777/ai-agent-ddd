package cn.apple.test.domain;

import cn.apple.config.UploadFileValidator;
import cn.apple.config.UploadProperties;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class UploadFileValidatorTest {

    private UploadFileValidator validator;

    @Before
    public void setUp() {
        UploadProperties properties = new UploadProperties();
        properties.setMaxFileSize(DataSize.ofKilobytes(64));
        validator = new UploadFileValidator(properties);
    }

    @Test
    public void shouldAcceptFilesWhoseDeclarationAndSignatureMatch() throws Exception {
        assertEquals(".pdf", validator.validateAndExtension(file(
                "case.pdf", "application/pdf", "%PDF-1.7\nbody".getBytes(StandardCharsets.US_ASCII))));
        assertEquals(".txt", validator.validateAndExtension(file(
                "statement.txt", "text/plain", "合法取得的证据说明".getBytes(StandardCharsets.UTF_8))));
        assertEquals(".docx", validator.validateAndExtension(file(
                "pleading.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", validDocx())));
    }

    @Test
    public void shouldRejectFileMasqueradingAsPdf() {
        MockMultipartFile file = file("evidence.pdf", "application/pdf",
                "<script>not a pdf</script>".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalArgumentException.class, () -> validator.validateAndExtension(file));
    }

    @Test
    public void shouldRejectExtensionAndDeclaredMimeMismatch() {
        MockMultipartFile file = file("evidence.pdf", "image/png",
                "%PDF-1.7".getBytes(StandardCharsets.US_ASCII));

        assertThrows(IllegalArgumentException.class, () -> validator.validateAndExtension(file));
    }

    @Test
    public void shouldRejectZipThatIsNotAWordDocument() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("payload.bin"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
        MockMultipartFile file = file("evidence.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes.toByteArray());

        assertThrows(IllegalArgumentException.class, () -> validator.validateAndExtension(file));
    }

    @Test
    public void shouldRejectBinaryContentDeclaredAsText() {
        MockMultipartFile file = file("evidence.txt", "text/plain", new byte[]{0x41, 0x00, 0x42});

        assertThrows(IllegalArgumentException.class, () -> validator.validateAndExtension(file));
    }

    @Test
    public void shouldRejectEmptyAndOversizedFiles() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndExtension(file("empty.txt", "text/plain", new byte[0])));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateAndExtension(file("large.txt", "text/plain", new byte[65 * 1024])));
    }

    private MockMultipartFile file(String filename, String contentType, byte[] content) {
        return new MockMultipartFile("file", filename, contentType, content);
    }

    private byte[] validDocx() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<document/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
