
package com.rks.rag.service.impl;

import cn.hutool.core.lang.Assert;
import com.rks.rag.dto.StoredFileDTO;
import com.rks.rag.service.FileStorageService;
import com.rks.rag.util.FileTypeDetector;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 本地文件存储服务实现
 *
 * <p>
 * 基于 S3 协议的文件存储服务实现，支持文件上传、读取和删除。
 * 使用 Apache Tika 进行 MIME 类型检测，生成 UUID 作为存储键名。
 * </p>
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>MultipartFile 上传 - 接收 Web 上传的文件并存储到 S3</li>
 *   <li>字节数组上传 - 直接存储字节内容到 S3</li>
 *   <li>流读取 - 根据 S3 URL 读取文件内容</li>
 *   <li>文件删除 - 根据 URL 删除存储的文件</li>
 * </ul>
 *
 * @see FileStorageService
 */
@Service
@RequiredArgsConstructor
public class LocalFileStorageServiceImpl implements FileStorageService {

    /** S3 客户端，用于与对象存储交互 */
    private final S3Client s3Client;

    /** Tika MIME 类型检测器 */
    private static final Tika TIKA = new Tika();

    /**
     * 上传 MultipartFile 到 S3 存储
     *
     * <p>
     * 流程：检测 MIME 类型 → 生成 UUID 键名 → 上传到 S3 → 返回文件信息。
     * </p>
     *
     * @param bucketName 存储桶名称
     * @param file       上传的MultipartFile
     * @return 存储结果（包含URL、检测到的文件类型、原始文件名等）
     */
    @Override
    @SneakyThrows
    public StoredFileDTO upload(String bucketName, MultipartFile file) {
        Assert.notBlank(bucketName, "bucketName 不能为空");
        Assert.isFalse(file == null || file.isEmpty(), "上传文件不能为空");

        String originalFilename = file.getOriginalFilename();
        long size = file.getSize();

        String detected;
        try (InputStream is = file.getInputStream()) {
            detected = TIKA.detect(is, originalFilename);
        }

        try (InputStream uploadIs = file.getInputStream()) {
            return uploadInternal(bucketName, uploadIs, size, originalFilename, detected);
        }
    }

    /**
     * 上传字节数组内容到 S3 存储
     *
     * @param bucketName        存储桶名称
     * @param content           文件字节内容
     * @param originalFilename  原始文件名
     * @param contentType       内容类型（可选，为空时自动检测）
     * @return 存储结果
     */
    @Override
    public StoredFileDTO upload(String bucketName, byte[] content, String originalFilename, String contentType) {
        Assert.notBlank(bucketName, "bucketName 不能为空");
        Assert.notNull(content, "上传内容不能为空");
        String detected = contentType;
        if (detected == null || detected.isBlank()) {
            detected = TIKA.detect(content, originalFilename);
        }
        return uploadInternal(bucketName, new java.io.ByteArrayInputStream(content), content.length, originalFilename, detected);
    }

    /**
     * 根据 S3 URL 打开文件输入流
     *
     * @param url S3 协议的 URL（格式：s3://bucket/key）
     * @return 文件输入流
     */
    @Override
    public InputStream openStream(String url) {
        S3Location loc = parseS3Url(url);
        return s3Client.getObject(b -> b.bucket(loc.bucket()).key(loc.key()));
    }

    /**
     * 根据 URL 删除存储的文件
     *
     * @param url S3 协议的 URL
     */
    @Override
    @SneakyThrows
    public void deleteByUrl(String url) {
        FileSystemUtils.deleteRecursively(Path.of(url));
    }

    /**
     * 构造 S3 URL
     *
     * @param bucket 存储桶名称
     * @param key    对象键名
     * @return s3://bucket/key 格式的 URL
     */
    private String toS3Url(String bucket, String key) {
        return "s3://" + bucket + "/" + key;
    }

    /**
     * 解析 S3 URL，提取存储桶和键名
     *
     * <p>
     * 解析 s3://bucket/key 格式的 URL，返回存储桶和键名。
     * 验证 URL 格式的合法性。
     * </p>
     *
     * @param url S3 协议的 URL
     * @return 解析后的存储桶和键名
     * @throws IllegalArgumentException URL 格式非法时抛出
     */
    private S3Location parseS3Url(String url) {
        try {
            URI uri = URI.create(url);
            if (!"s3".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("Unsupported url scheme: " + url);
            }

            String bucket = uri.getHost();
            String path = uri.getPath(); // /key...
            if (bucket == null || bucket.isBlank()) {
                throw new IllegalArgumentException("Invalid s3 url(bucket missing): " + url);
            }

            String key = (path != null && path.startsWith("/")) ? path.substring(1) : path;
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Invalid s3 url(key missing): " + url);
            }

            return new S3Location(bucket, key);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid s3 url: " + url, e);
        }
    }

    /** S3 位置信息记录类 */
    private record S3Location(String bucket, String key) {
    }

    /**
     * 内部方法：执行实际上传
     *
     * <p>
     * 生成 UUID 键名 → 上传到 S3 → 构造返回结果。
     * </p>
     *
     * @param bucketName          存储桶名称
     * @param inputStream         输入流
     * @param size                文件大小
     * @param originalFilename    原始文件名
     * @param detectedContentType 检测到的内容类型
     * @return 存储结果
     */
    private StoredFileDTO uploadInternal(String bucketName,
                                         InputStream inputStream,
                                         long size,
                                         String originalFilename,
                                         String detectedContentType) {
        String safeName = originalFilename == null ? "" : originalFilename;
        String suffix = extractSuffix(safeName);

        String s3Key = UUID.randomUUID().toString().replace("-", "")
                + (suffix.isBlank() ? "" : "." + suffix);

        s3Client.putObject(
                b -> b.bucket(bucketName)
                        .key(s3Key)
                        .contentType(detectedContentType)
                        .build(),
                RequestBody.fromInputStream(inputStream, size)
        );

        String url = toS3Url(bucketName, s3Key);
        String detectedType = FileTypeDetector.detectType(originalFilename, detectedContentType);

        return StoredFileDTO.builder()
                .url(url)
                .detectedType(detectedType)
                .size(size)
                .originalFilename(originalFilename)
                .build();
    }

    private String extractSuffix(String filename) {
        if (filename == null) return "";
        int idx = filename.lastIndexOf('.');
        if (idx < 0 || idx == filename.length() - 1) {
            return "";
        }
        return filename.substring(idx + 1).trim();
    }

}
