
package com.rks.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 已存储文件信息
 *
 * <p>
 * 表示文件上传后存储在 S3 兼容对象存储中的文件元信息。
 * </p>
 *
 * @see com.rks.rag.service.FileStorageService
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StoredFileDTO {

    private String url;

    private String detectedType;

    private Long size;

    private String originalFilename;
}
