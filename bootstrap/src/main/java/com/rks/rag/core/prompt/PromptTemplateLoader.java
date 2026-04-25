

package com.rks.rag.core.prompt;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示模板加载器 - 从类路径加载并缓存 Prompt 模板
 *
 * <p>
 * PromptTemplateLoader 负责从类路径下加载提示词模板文件，
 * 支持模板变量填充功能，并将加载过的模板缓存以提高性能。
 * </p>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>模板加载</b>：从 classpath 加载模板文件</li>
 *   <li><b>模板缓存</b>：使用 ConcurrentHashMap 缓存已加载的模板</li>
 *   <li><b>模板渲染</b>：将占位符替换为实际值</li>
 *   <li><b>格式清理</b>：清理模板中的多余空白和格式问题</li>
 * </ul>
 *
 * <h2>路径格式</h2>
 * <p>
 * 路径支持两种格式：
 * </p>
 * <ul>
 *   <li>直接路径：如 "prompts/rag-template.txt"</li>
 *   <li>classpath 前缀：如 "classpath:prompts/rag-template.txt"</li>
 * </ul>
 *
 * <h2>模板变量</h2>
 * <p>
 * 模板中的占位符使用 {{variable_name}} 格式，
 * 通过 render() 方法传入的 Map 进行替换。
 * </p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 加载模板
 * String template = promptTemplateLoader.load("prompts/rag.txt");
 *
 * // 渲染模板
 * String rendered = promptTemplateLoader.render("prompts/title.txt",
 *     Map.of("question", userQuestion, "title_max_chars", "30"));
 * }</pre>
 *
 * @see PromptTemplateUtils
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptTemplateLoader {

    private final ResourceLoader resourceLoader;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 加载指定路径的提示模板
     *
     * @param path 模板文件路径，支持classpath:前缀
     * @return 模板内容字符串
     * @throws IllegalArgumentException 当路径为空时抛出
     * @throws IllegalStateException    当模板文件不存在或读取失败时抛出
     */
    public String load(String path) {
        if (StrUtil.isBlank(path)) {
            throw new IllegalArgumentException("提示模板路径为空");
        }
        return cache.computeIfAbsent(path, this::readResource);
    }

    /**
     * 渲染提示模板，将模板中的占位符替换为实际值
     *
     * @param path  模板文件路径
     * @param slots 占位符映射表，键为占位符名称，值为替换内容
     * @return 渲染后的完整提示文本
     */
    public String render(String path, Map<String, String> slots) {
        String template = load(path);
        String filled = PromptTemplateUtils.fillSlots(template, slots);
        return PromptTemplateUtils.cleanupPrompt(filled);
    }

    /**
     * 从资源路径读取模板内容
     *
     * @param path 模板文件路径
     * @return 模板内容字符串
     * @throws IllegalStateException 当模板文件不存在或读取失败时抛出
     */
    private String readResource(String path) {
        String location = path.startsWith("classpath:") ? path : "classpath:" + path;
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("提示词模板路径不存在：" + path);
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("读取提示模板失败，路径：{}", path, e);
            throw new IllegalStateException("读取提示模板失败，路径：" + path, e);
        }
    }
}
