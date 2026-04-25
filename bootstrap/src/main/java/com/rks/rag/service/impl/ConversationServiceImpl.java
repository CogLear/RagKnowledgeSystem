
package com.rks.rag.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rks.framework.context.UserContext;
import com.rks.framework.convention.ChatMessage;
import com.rks.framework.convention.ChatRequest;
import com.rks.framework.exception.ClientException;
import com.rks.infra.chat.LLMService;
import com.rks.rag.config.MemoryProperties;
import com.rks.rag.controller.request.ConversationCreateRequest;
import com.rks.rag.controller.request.ConversationUpdateRequest;
import com.rks.rag.controller.vo.ConversationVO;
import com.rks.rag.core.prompt.PromptTemplateLoader;
import com.rks.rag.dao.entity.ConversationDO;
import com.rks.rag.dao.entity.ConversationMessageDO;
import com.rks.rag.dao.entity.ConversationSummaryDO;
import com.rks.rag.dao.mapper.ConversationMapper;
import com.rks.rag.dao.mapper.ConversationMessageMapper;
import com.rks.rag.dao.mapper.ConversationSummaryMapper;
import com.rks.rag.service.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.rks.rag.constant.RAGConstant.CONVERSATION_TITLE_PROMPT_PATH;


/**
 * 会话服务实现类 - 会话生命周期管理
 *
 * <p>
 * 会话服务实现类负责处理会话的创建、更新、重命名和删除等核心业务逻辑。
 * 会话数据采用软删除策略，通过 deleted 字段标记是否被删除。
 * </p>
 *
 * <h2>数据模型</h2>
 * <ul>
 *   <li>{@link ConversationDO} - 会话基本信息</li>
 *   <li>{@link ConversationMessageDO} - 会话消息列表</li>
 *   <li>{@link ConversationSummaryDO} - 会话摘要信息</li>
 * </ul>
 *
 * <h2>事务管理</h2>
 * <p>
 * 删除操作使用 @Transactional 注解确保原子性：
 * 删除会话时同时删除关联的消息和摘要数据。
 * </p>
 *
 * @see ConversationService
 * @see ConversationMapper
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    /** 会话基本信息 Mapper */
    private final ConversationMapper conversationMapper;
    /** 会话消息 Mapper */
    private final ConversationMessageMapper messageMapper;
    /** 会话摘要 Mapper */
    private final ConversationSummaryMapper summaryMapper;
    /** 记忆配置属性 */
    private final MemoryProperties memoryProperties;
    /** Prompt 模板加载器 */
    private final PromptTemplateLoader promptTemplateLoader;
    /** LLM 服务，用于生成会话标题 */
    private final LLMService llmService;

    /**
     * 获取指定用户的会话列表
     *
     * <p>
     * 查询逻辑：
     * </p>
     * <ol>
     *   <li>校验 userId 是否为空</li>
     *   <li>查询未删除的会话列表</li>
     *   <li>按最后更新时间倒序排列</li>
     *   <li>转换为 VO 对象返回</li>
     * </ol>
     *
     * @param userId 用户ID
     * @return 会话视图列表，如果无结果返回空列表
     */
    @Override
    public List<ConversationVO> listByUserId(String userId) {
        if (StrUtil.isBlank(userId)) {
            return List.of();
        }

        List<ConversationDO> records = conversationMapper.selectList(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getDeleted, 0)
                        .orderByDesc(ConversationDO::getLastTime)
        );
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        return records.stream()
                .map(item -> ConversationVO.builder()
                        .conversationId(item.getConversationId())
                        .title(item.getTitle())
                        .lastTime(item.getLastTime())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 创建或更新会话
     *
     * <p>
     * 业务逻辑：
     * </p>
     * <ol>
     *   <li>校验用户ID是否为空</li>
     *   <li>根据会话ID和用户ID查询是否已存在会话</li>
     *   <li>不存在则创建新会话，并调用 LLM 生成标题</li>
     *   <li>已存在则只更新最后更新时间</li>
     * </ol>
     *
     * <h3>标题生成</h3>
     * <p>
     * 新建会话时，通过 {@link #generateTitleFromQuestion(String)} 方法
     * 使用 LLM 根据用户问题生成简短标题。如果生成失败，默认使用"新对话"。
     * </p>
     *
     * @param request 创建请求对象
     * @throws ClientException 当用户ID为空时抛出
     * @see #generateTitleFromQuestion(String)
     */
    @Override
    public void createOrUpdate(ConversationCreateRequest request) {
        String userId = request.getUserId();
        String conversationId = request.getConversationId();
        String question = request.getQuestion();
        if (StrUtil.isBlank(userId)) {
            throw new ClientException("用户信息缺失");
        }

        ConversationDO existing = conversationMapper.selectOne(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getConversationId, conversationId)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getDeleted, 0)
        );

        if (existing == null) {
            String title = generateTitleFromQuestion(question);
            ConversationDO record = ConversationDO.builder()
                    .conversationId(conversationId)
                    .userId(userId)
                    .title(title)
                    .lastTime(request.getLastTime())
                    .build();
            conversationMapper.insert(record);
            return;
        }

        existing.setLastTime(request.getLastTime());
        conversationMapper.updateById(existing);
    }

    /**
     * 重命名会话
     *
     * <p>
     * 业务逻辑：
     * </p>
     * <ol>
     *   <li>获取当前登录用户ID</li>
     *   <li>校验会话ID和用户ID是否为空</li>
     *   <li>校验标题是否为空</li>
     *   <li>校验标题长度是否超过配置的最大值</li>
     *   <li>查询会话是否存在且属于该用户</li>
     *   <li>更新会话标题</li>
     * </ol>
     *
     * <h3>标题长度限制</h3>
     * <p>
     * 标题最大长度由 MemoryProperties.titleMaxLength 配置。
     * 超过最大长度的标题会被拒绝并抛出异常。
     * </p>
     *
     * @param conversationId 会话ID
     * @param request       更新请求对象，包含新标题
     * @throws ClientException 当参数校验失败或会话不存在时抛出
     */
    @Override
    public void rename(String conversationId, ConversationUpdateRequest request) {
        String userId = UserContext.getUserId();
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            throw new ClientException("会话信息缺失");
        }

        String title = request.getTitle();
        if (StrUtil.isBlank(title)) {
            throw new ClientException("会话名称不能为空");
        }
        int maxLen = memoryProperties.getTitleMaxLength();
        if (title.length() > maxLen) {
            throw new ClientException("会话名称长度不能超过" + maxLen + "个字符");
        }

        ConversationDO record = conversationMapper.selectOne(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getConversationId, conversationId)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getDeleted, 0)
        );
        if (record == null) {
            throw new ClientException("会话不存在");
        }

        record.setTitle(title.trim());
        conversationMapper.updateById(record);
    }

    /**
     * 删除会话及其关联数据
     *
     * <p>
     * 这是一个事务性操作，确保以下删除操作的原子性：
     * </p>
     * <ol>
     *   <li>获取当前登录用户ID</li>
     *   <li>校验会话ID和用户ID是否为空</li>
     *   <li>查询会话是否存在且属于该用户</li>
     *   <li>删除会话基本信息</li>
     *   <li>删除该会话下的所有消息</li>
     *   <li>删除该会话的摘要信息</li>
     * </ol>
     *
     * <p>
     * 注意：这是物理删除（从数据库中真正删除记录），
     * 而非软删除（仅标记 deleted 字段）。
     * </p>
     *
     * @param conversationId 会话ID
     * @throws ClientException 当参数校验失败或会话不存在时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void delete(String conversationId) {
        String userId = UserContext.getUserId();
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            throw new ClientException("会话信息缺失");
        }

        ConversationDO record = conversationMapper.selectOne(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getConversationId, conversationId)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getDeleted, 0)
        );
        if (record == null) {
            throw new ClientException("会话不存在");
        }

        conversationMapper.deleteById(record.getId());
        messageMapper.delete(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getDeleted, 0)
        );
        summaryMapper.delete(
                Wrappers.lambdaQuery(ConversationSummaryDO.class)
                        .eq(ConversationSummaryDO::getConversationId, conversationId)
                        .eq(ConversationSummaryDO::getUserId, userId)
                        .eq(ConversationSummaryDO::getDeleted, 0)
        );
    }

    /**
     * 根据用户问题生成会话标题
     *
     * <p>
     * 该方法使用 LLM 根据用户的问题自动生成简短会话标题：
     * </p>
     * <ol>
     *   <li>加载标题生成 Prompt 模板</li>
     *   <li>将问题渲染到模板中</li>
     *   <li>调用 LLM 服务生成标题</li>
     *   <li>如果调用失败，返回默认标题"新对话"</li>
     * </ol>
     *
     * <h3>Prompt 模板变量</h3>
     * <ul>
     *   <li>title_max_chars - 标题最大字符数（由 MemoryProperties 配置）</li>
     *   <li>question - 用户的原始问题</li>
     * </ul>
     *
     * <h3>LLM 调用参数</h3>
     * <ul>
     *   <li>temperature: 0.7 - 适度创造性</li>
     *   <li>topP: 0.3 - 限制词汇选择范围</li>
     *   <li>thinking: false - 不启用深度思考</li>
     * </ul>
     *
     * @param question 用户的问题
     * @return 生成的标题，如果生成失败则返回默认标题"新对话"
     */
    private String generateTitleFromQuestion(String question) {
        int maxLen = memoryProperties.getTitleMaxLength();
        if (maxLen <= 0) {
            maxLen = 30;
        }
        String prompt = promptTemplateLoader.render(
                CONVERSATION_TITLE_PROMPT_PATH,
                Map.of(
                        "title_max_chars", String.valueOf(maxLen),
                        "question", question
                )
        );

        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(ChatMessage.user(prompt)))
                    .temperature(0.7D)
                    .topP(0.3D)
                    .thinking(false)
                    .build();

            return llmService.chat(request);
        } catch (Exception ex) {
            log.warn("生成会话标题失败", ex);
            return "新对话";
        }
    }
}
