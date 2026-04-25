
package com.rks.rag.core.intent;

/**
 * 意图节点注册表接口 - 在运行期快速获取意图树和节点信息
 *
 * <p>
 * IntentNodeRegistry 提供了在运行时快速查找意图节点的能力。
 * 意图树在系统启动时从数据库加载到内存中，注册表提供了高效的节点访问接口。
 * </p>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>节点查找</b>：根据节点 ID 快速获取节点信息</li>
 *   <li><b>缓存访问</b>：避免每次都查询数据库</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <ul>
 *   <li>IntentGuidanceService 根据节点 ID 解析选项名称</li>
 *   <li>RetrievalEngine 根据意图节点 ID 分组检索结果</li>
 *   <li>构建意图树的全路径名称</li>
 * </ul>
 *
 * @see IntentNode
 */
public interface IntentNodeRegistry {

    /**
     * 根据节点 ID 获取节点
     */
    IntentNode getNodeById(String id);
}
