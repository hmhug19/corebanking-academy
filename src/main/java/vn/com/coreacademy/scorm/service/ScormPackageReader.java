package vn.com.coreacademy.scorm.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Element;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.scorm.config.ScormProperties;

/** Bounded ZIP parsing without extracting attacker-supplied paths or resolving external XML entities. */
@Component
@RequiredArgsConstructor
public class ScormPackageReader {
    private final ScormProperties properties;

    public record Entry(Path file, long size, String mime, String checksum) { }
    public record Package(String title, String launch, BigDecimal mastery, Map<String, Entry> files,
                          List<String> warnings, Path directory) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    public Package read(MultipartFile upload) throws IOException {
        if (upload.isEmpty() || upload.getSize() > properties.getMaxZipBytes()
            || upload.getOriginalFilename() == null
            || !upload.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw invalid("Chọn tệp SCORM .zip trong giới hạn 100 MB.");
        }
        Files.createDirectories(properties.getTempDir());
        Path directory = Files.createTempDirectory(properties.getTempDir(), "scorm-import-");
        Map<String, Entry> files = new LinkedHashMap<>();
        try {
            long total = 0;
            int count = 0;
            try (var input = new ZipInputStream(upload.getInputStream(), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    String name = safePath(entry.getName());
                    if (++count > properties.getMaxFiles()) throw invalid("Gói chứa quá nhiều tệp/thư mục.");
                    if (entry.isDirectory()) continue;
                    if (files.containsKey(name)) throw invalid("Gói chứa đường dẫn tệp trùng nhau.");
                    Path target = directory.resolve(Integer.toString(count));
                    long size = 0;
                    var digest = digest();
                    try (var output = Files.newOutputStream(target)) {
                        byte[] bytes = new byte[65536];
                        int read;
                        while ((read = input.read(bytes)) != -1) {
                            size += read;
                            total += read;
                            if (total > properties.getMaxExpandedBytes()
                                || total > Math.max(1024 * 1024L, upload.getSize() * 200)) {
                                throw invalid("Gói vượt dung lượng giải nén hoặc tỷ lệ nén cho phép.");
                            }
                            digest.update(bytes, 0, read);
                            output.write(bytes, 0, read);
                        }
                    }
                    files.put(name, new Entry(target, size, mime(name), HexFormat.of().formatHex(digest.digest())));
                }
            }
            return manifest(files, directory);
        } catch (Exception exception) {
            new Package("", "", null, Map.of(), List.of(), directory).close();
            if (exception instanceof BusinessException business) throw business;
            if (exception instanceof IOException io) throw io;
            throw invalid("Không đọc được manifest SCORM 1.2 trong tệp ZIP.");
        }
    }

    private Package manifest(Map<String, Entry> original, Path directory) throws Exception {
        List<String> manifests = original.keySet().stream().filter(n -> n.endsWith("imsmanifest.xml")).toList();
        if (manifests.size() != 1) throw invalid("Gói phải chứa đúng một imsmanifest.xml.");
        String manifestName = manifests.get(0);
        if (original.get(manifestName).size() > 1024 * 1024) throw invalid("Manifest quá lớn.");
        String base = manifestName.substring(0, manifestName.length() - "imsmanifest.xml".length());
        Map<String, Entry> files = new LinkedHashMap<>();
        original.forEach((name, entry) -> { if (name.startsWith(base)) files.put(name.substring(base.length()), entry); });
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        org.w3c.dom.Document document;
        try (InputStream input = Files.newInputStream(files.get("imsmanifest.xml").file())) {
            document = factory.newDocumentBuilder().parse(input);
        }
        var versions = document.getElementsByTagNameNS("*", "schemaversion");
        if (versions.getLength() == 0 || !versions.item(0).getTextContent().trim().equals("1.2")) {
            throw invalid("Chỉ hỗ trợ SCORM 1.2; gói SCORM 2004 cần xuất lại đúng phiên bản.");
        }
        var resources = document.getElementsByTagNameNS("*", "resource");
        List<Element> scos = new ArrayList<>();
        for (int i = 0; i < resources.getLength(); i++) {
            Element resource = (Element) resources.item(i);
            if ("sco".equalsIgnoreCase(resource.getAttributeNS("http://www.adlnet.org/xsd/adlcp_rootv1p2", "scormtype"))) {
                scos.add(resource);
            }
        }
        if (scos.size() != 1) throw invalid("Phiên bản hiện tại hỗ trợ SCORM 1.2 có một SCO (Single SCO).");
        String launch = scos.get(0).getAttribute("href");
        String path = safePath(launch.split("[?#]", 2)[0]);
        if (!files.containsKey(path) || !files.get(path).mime().equals("text/html")) {
            throw invalid("Không tìm thấy trang HTML mở bài được khai báo trong manifest.");
        }
        var titles = document.getElementsByTagNameNS("*", "title");
        String title = titles.getLength() == 0 ? "Bài giảng SCORM" : titles.item(0).getTextContent().strip();
        if (title.isBlank() || title.length() > 255) throw invalid("Tên bài giảng trong manifest không hợp lệ.");
        BigDecimal mastery = null;
        var scores = document.getElementsByTagNameNS("*", "masteryscore");
        if (scores.getLength() > 0) {
            mastery = new BigDecimal(scores.item(0).getTextContent().trim());
            if (mastery.signum() < 0 || mastery.compareTo(new BigDecimal("100")) > 0) throw invalid("Điểm đạt không hợp lệ.");
        }
        List<String> warnings = new ArrayList<>();
        if (files.get(path).size() < 65536
            && Files.readString(files.get(path).file()).contains("Not implemented yet")) {
            warnings.add("Trang mở bài của gói chỉ ghi Not implemented yet; đây là mẫu đóng gói, chưa có runtime/quiz chạy được.");
        }
        return new Package(title, launch, mastery, files, warnings, directory);
    }

    public static String safePath(String value) {
        String name = Normalizer.normalize(value, Normalizer.Form.NFC);
        if (name.isBlank() || name.length() > 1000 || name.startsWith("/") || name.contains("\\")
            || name.contains(":") || name.contains("%") || name.contains("\u0000")
            || name.chars().anyMatch(Character::isISOControl)
            || name.contains("?") || name.contains("#")
            || java.util.Arrays.stream(name.split("/", -1)).anyMatch(p -> p.equals("..") || p.equals("."))) {
            throw invalid("Gói chứa đường dẫn không an toàn hoặc không hợp lệ.");
        }
        return name;
    }

    private static String mime(String path) {
        String suffix = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (suffix) {
            case "html", "htm" -> "text/html";
            case "js", "mjs" -> "text/javascript";
            case "css" -> "text/css";
            case "json" -> "application/json";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "svg" -> "image/svg+xml";
            case "webp" -> "image/webp";
            case "mp4" -> "video/mp4";
            case "mp3" -> "audio/mpeg";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            case "pdf" -> "application/pdf";
            case "xml", "xsd" -> "application/xml";
            default -> "application/octet-stream";
        };
    }

    public static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public static String hash(String value) {
        return HexFormat.of().formatHex(digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    public static BusinessException invalid(String message) {
        return BusinessException.validation("file", "INVALID_FORMAT", message);
    }
}
