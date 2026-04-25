package com.rks.rag.service;

import com.rks.rag.dto.StoredFileDTO;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * 文件存储服务接口
 *
 * <p>
 * 基于 S3 协议的文件存储服务接口，支持文件上传、读取和删除。
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
 * @see com.rks.rag.service.impl.LocalFileStorageServiceImpl
 */
public interface FileStorageService {

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
    StoredFileDTO upload(String bucketName, MultipartFile file);

    /**
     * 上传字节数组内容到 S3 存储
     *
     * @param bucketName        存储桶名称
     * @param content           文件字节内容
     * @param originalFilename  原始文件名
     * @param contentType       内容类型（可选，为空时自动检测）
     * @return 存储结果
     */
    StoredFileDTO upload(String bucketName, byte[] content, String originalFilename, String contentType);

    /**
     * 根据 S3 URL 打开文件输入流
     *
     * @param url S3 协议的 URL（格式：s3://bucket/key）
     * @return 文件输入流
     */
    InputStream openStream(String url);

    /**
     * 根据 URL 删除存储的文件
     *
     * @param url S3 协议的 URL
     */
    void deleteByUrl(String url);
}