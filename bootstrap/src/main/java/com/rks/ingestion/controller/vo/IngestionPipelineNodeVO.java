
package com.rks.ingestion.controller.vo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 数据摄入流水线节点视图对象
 *
 * <p>
 * 包含流水线中单个节点的基本信息和配置。
 * </p>
 *
 * @see com.rks.ingestion.service.IngestionPipelineService
 */
@Data
public class IngestionPipelineNodeVO {

    private Long id;

    private String nodeId;

    private String nodeType;

    private JsonNode settings;

    private JsonNode condition;

    private String nextNodeId;
}
