import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { useTheme } from "../context/ThemeContext";

// 导航栏组件
function Navbar() {
  const { isDark, toggleTheme } = useTheme();
  const navigate = useNavigate();
  const [scrolled, setScrolled] = useState(false);

  useEffect(() => {
    const handleScroll = () => setScrolled(window.scrollY > 50);
    window.addEventListener("scroll", handleScroll);
    return () => window.removeEventListener("scroll", handleScroll);
  }, []);

  return (
    <nav
      className={`fixed top-0 left-0 right-0 z-50 transition-all duration-300 ${
        scrolled ? "nav-neo scrolled py-3" : "bg-transparent py-5"
      }`}
    >
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="flex items-center justify-between">
          {/* Logo */}
          <div className="flex items-center space-x-3 cursor-pointer" onClick={() => navigate("/")}>
            <div className="neo-logo">
              <span>R</span>
            </div>
            <span className="text-xl font-black text-neo text-gray-900 dark:text-white">
              RAG 知识库
            </span>
          </div>

          {/* 导航链接 */}
          <div className="hidden md:flex items-center space-x-8">
            <a
              href="#"
              className="text-gray-900 dark:text-white font-bold hover:text-[#FF6B9D] transition-colors"
            >
              首页
            </a>
            <a
              href="#about"
              className="text-gray-900 dark:text-white font-bold hover:text-[#FF6B9D] transition-colors"
            >
              关于项目
            </a>
            <a
              href="#features"
              className="text-gray-900 dark:text-white font-bold hover:text-[#FF6B9D] transition-colors"
            >
              功能
            </a>
            <a
              href="#tech"
              className="text-gray-900 dark:text-white font-bold hover:text-[#FF6B9D] transition-colors"
            >
              技术栈
            </a>
            <a
              href="#scenarios"
              className="text-gray-900 dark:text-white font-bold hover:text-[#FF6B9D] transition-colors"
            >
              场景
            </a>
          </div>

          {/* 主题切换 */}
          <button
            onClick={toggleTheme}
            className="neo-btn p-2 !px-4 !py-2 cursor-pointer"
            aria-label="切换主题"
          >
            {isDark ? (
              <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
                <path d="M17.293 13.293A8 8 0 016.707 2.707a8.001 8.001 0 1010.586 10.586z" />
              </svg>
            ) : (
              <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
                <path
                  fillRule="evenodd"
                  d="M10 2a1 1 0 011 1v1a1 1 0 11-2 0V3a1 1 0 011-1zm4 8a4 4 0 11-8 0 4 4 0 018 0zm-.464 4.95l.707.707a1 1 0 001.414-1.414l-.707-.707a1 1 0 00-1.414 1.414zm2.12-10.607a1 1 0 010 1.414l-.706.707a1 1 0 11-1.414-1.414l.707-.707a1 1 0 011.414 0zM17 11a1 1 0 100-2h-1a1 1 0 100 2h1zm-7 4a1 1 0 011 1v1a1 1 0 11-2 0v-1a1 1 0 011-1zM5.05 6.464A1 1 0 106.465 5.05l-.708-.707a1 1 0 00-1.414 1.414l.707.707zm1.414 8.486l-.707.707a1 1 0 01-1.414-1.414l.707-.707a1 1 0 011.414 1.414zM4 11a1 1 0 100-2H3a1 1 0 000 2h1z"
                  clipRule="evenodd"
                />
              </svg>
            )}
          </button>
        </div>
      </div>
    </nav>
  );
}

// 英雄区组件
function HeroSection() {
  const navigate = useNavigate();

  return (
    <section className="min-h-screen flex items-center justify-center px-4 pt-20 relative overflow-hidden">
      {/* 几何装饰 */}
      <div
        className="geo-circle w-96 h-96 -top-20 -left-20 border-8"
        style={{ borderColor: "#FFD93D" }}
      ></div>
      <div
        className="geo-circle w-64 h-64 bottom-20 right-10 border-6"
        style={{ borderColor: "#FF6B9D" }}
      ></div>
      <div className="geo-wave top-1/3 left-0 rotate-6"></div>
      <div className="geo-zigzag bottom-1/4 right-20"></div>

      <div className="max-w-4xl mx-auto text-center relative z-10">
        {/* 标签 */}
        <div className="neo-tag mb-8">
          <span className="w-3 h-3 bg-white rounded-full animate-pulse"></span>
          智能问答新时代
        </div>

        {/* 主标题 */}
        <h1 className="text-5xl sm:text-6xl md:text-7xl font-black text-neo text-gray-900 dark:text-white mb-6 leading-none tracking-tighter">
          RAG 知识库系统
        </h1>

        {/* 副标题 */}
        <p className="text-lg sm:text-xl text-gray-600 dark:text-gray-400 mb-6 max-w-2xl mx-auto font-medium tracking-wide">
          检索 → 理解 → 生成：端到端 RAG 流水线架构
        </p>
        <p className="text-sm sm:text-base text-gray-500 dark:text-gray-500 mb-10 max-w-2xl mx-auto font-medium tracking-wide">
          支持查询改写 · 三级意图分类 · 多路并行检索 · MCP 工具调用
        </p>

        {/* 按钮组 */}
        <div className="flex flex-col sm:flex-row items-center justify-center gap-4">
          <button
            onClick={() => navigate("/chat")}
            className="neo-btn neo-btn-primary cursor-pointer"
          >
            <span className="flex items-center justify-center gap-2">
              <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"
                />
              </svg>
              进入聊天
              <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M9 5l7 7-7 7"
                />
              </svg>
            </span>
          </button>

          <button
            onClick={() => navigate("/admin")}
            className="neo-btn neo-btn-secondary cursor-pointer"
          >
            <span className="flex items-center justify-center gap-2">
              <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"
                />
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                />
              </svg>
              管理后台
              <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M9 5l7 7-7 7"
                />
              </svg>
            </span>
          </button>
        </div>

        {/* 统计数据 */}
        <div className="mt-16 grid grid-cols-3 gap-6 max-w-xl mx-auto">
          {[
            { value: "RAG", label: "检索增强生成", bgColor: "#FFD93D" },
            { value: "MCP", label: "协议工具调用", bgColor: "#FF6B9D" },
            { value: "向量", label: "Milvus 存储", bgColor: "#4ECDC4" }
          ].map((stat, i) => (
            <div key={i} className={`neo-card p-4 ${i % 2 === 0 ? "neo-card-tilt" : ""}`}>
              <div className="stat-neo" style={{ color: stat.bgColor }}>
                {stat.value}
              </div>
              <div className="text-sm font-bold text-gray-500 dark:text-gray-400 mt-2 uppercase tracking-wider">
                {stat.label}
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

// 项目介绍组件
function AboutSection() {
  return (
    <section id="about" className="py-24 px-4 relative">
      <div className="geo-zigzag top-0 left-1/4"></div>

      <div className="max-w-6xl mx-auto">
        <div className="text-center mb-16">
          <h2 className="text-4xl sm:text-5xl font-black text-neo text-gray-900 dark:text-white mb-4 tracking-tighter">
            关于项目
          </h2>
          <div className="neo-divider w-24 mx-auto"></div>
        </div>

        <div className="grid md:grid-cols-2 gap-12 items-center">
          <div>
            <h3 className="text-2xl font-bold text-gray-900 dark:text-white mb-4 tracking-tight">
              端到端 RAG 流水线
            </h3>
            <p className="text-gray-600 dark:text-gray-400 leading-relaxed mb-6 font-medium">
              <strong>Query Rewrite</strong> → <strong>Intent Classification</strong> → <strong>Multi-Channel Retrieval</strong> →
              <strong> Rerank</strong> → <strong>Prompt Building</strong> → <strong>LLM Generation</strong>
            </p>
            <p className="text-gray-600 dark:text-gray-400 leading-relaxed mb-6 font-medium">
              系统核心在于<strong>查询改写</strong>优化检索召回、<strong>三级意图树</strong>精准判断用户意图（KB/MCP/SYSTEM）、
              <strong>多路检索</strong>融合向量与关键词匹配、<strong>MCP 协议</strong>动态调用外部工具。
            </p>
            <p className="text-gray-600 dark:text-gray-400 leading-relaxed font-medium">
              支持 Bailian · Ollama · SiliconFlow 多模型接入，Milvus 向量存储，Redis 分布式缓存。
            </p>
          </div>
          <div className="grid grid-cols-2 gap-4">
            {[
              { icon: "⚡", title: "查询改写", desc: "LLM 优化 query", color: "#FFD93D" },
              { icon: "🎯", title: "意图分类", desc: "三级树精准判断", color: "#FF6B9D" },
              { icon: "🔀", title: "多路检索", desc: "向量+关键词双通道", color: "#4ECDC4" },
              {
                icon: "🔌",
                title: "MCP 工具",
                desc: "外部工具动态调用",
                color: "#7B2CBF"
              }
            ].map((item, i) => (
              <div key={i} className={`neo-card p-4 ${i % 2 === 0 ? "" : "rotate-2"}`}>
                <div
                  className="neo-icon mb-2"
                  style={{ background: item.color, transform: "rotate(-5deg)" }}
                >
                  {item.icon}
                </div>
                <div className="font-black text-gray-900 dark:text-white">{item.title}</div>
                <div className="text-sm font-medium text-gray-500 dark:text-gray-400">
                  {item.desc}
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}

// 核心功能展示组件
function FeaturesSection() {
  const features = [
    {
      title: "查询改写 Query Rewrite",
      desc: "基于 LLM 将用户 query 同义扩展、分解，提升检索召回率和答案质量",
      color: "#FFD93D"
    },
    {
      title: "三级意图分类",
      desc: "DOMAIN → CATEGORY → TOPIC 树形结构，精准区分 KB检索 / MCP工具 / SYSTEM直接回答",
      color: "#FF6B9D"
    },
    {
      title: "多路并行检索",
      desc: "Vector Global Search + Intent Directed Search 双通道融合，配合 Rerank 返回最优结果",
      color: "#4ECDC4"
    },
    {
      title: "MCP 工具调用",
      desc: "Model Context Protocol 协议，支持外部工具动态注册与调用，获取实时数据",
      color: "#7B2CBF"
    },
    {
      title: "智能文档分块",
      desc: "Heading / Paragraph / CodeFence / Atomic 多种策略，语义完整性与向量匹配兼顾",
      color: "#FFD93D"
    },
    {
      title: "多模型与向量库",
      desc: "Bailian · Ollama · SiliconFlow 多模型切换，Milvus 向量库 + Redis 缓存",
      color: "#FF6B9D"
    }
  ];

  return (
    <section id="features" className="py-24 px-4 relative">
      <div
        className="geo-circle w-80 h-80 -top-20 -left-20 border-8"
        style={{ borderColor: "#FFD93D", opacity: 0.2 }}
      ></div>
      <div
        className="geo-circle w-64 h-64 bottom-20 right-10 border-6"
        style={{ borderColor: "#FF6B9D", opacity: 0.2 }}
      ></div>
      <div className="geo-zigzag top-10 right-1/4"></div>

      <div className="max-w-6xl mx-auto relative z-10">
        <div className="text-center mb-16">
          <h2 className="text-4xl sm:text-5xl font-black text-neo text-gray-900 dark:text-white mb-4 tracking-tighter">
            核心功能
          </h2>
          <p className="text-gray-600 dark:text-gray-400 max-w-2xl mx-auto font-medium">
            强大的功能特性，助您构建专业的智能问答系统
          </p>
          <div className="neo-divider w-24 mx-auto mt-4"></div>
        </div>

        <div className="grid sm:grid-cols-2 lg:grid-cols-3 gap-6">
          {features.map((feature, i) => (
            <div
              key={i}
              className={`group neo-card p-6 cursor-pointer ${i % 2 === 0 ? "" : "rotate-1"}`}
            >
              <div
                className="neo-icon mb-4 group-hover:rotate-12 transition-transform"
                style={{ background: feature.color }}
              >
                <svg
                  className="w-8 h-8"
                  fill="none"
                  stroke="white"
                  viewBox="0 0 24 24"
                  strokeWidth={2}
                >
                  {feature.title.includes("查询改写") && (
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357 2M15.357 2A8.003 8.003 0 019 15.357"
                    />
                  )}
                  {feature.title.includes("意图分类") && (
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4"
                    />
                  )}
                  {feature.title.includes("多路") && (
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
                    />
                  )}
                  {feature.title.includes("MCP") && (
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"
                    />
                  )}
                  {feature.title.includes("分块") && (
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      d="M4 5a1 1 0 011-1h14a1 1 0 011 1v2a1 1 0 01-1 1H5a1 1 0 01-1-1V5zM4 13a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H5a1 1 0 01-1-1v-6zM16 13a1 1 0 011-1h2a1 1 0 011 1v6a1 1 0 01-1 1h-2a1 1 0 01-1-1v-6z"
                    />
                  )}
                  {feature.title.includes("多模型") && (
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z"
                    />
                  )}
                </svg>
              </div>
              <h3 className="text-xl font-black text-gray-900 dark:text-white mb-2 tracking-tight">
                {feature.title}
              </h3>
              <p className="text-gray-600 dark:text-gray-400 text-sm leading-relaxed font-medium">
                {feature.desc}
              </p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

// 技术栈说明组件
function TechSection() {
  const techs = [
    { name: "Spring Boot", type: "Backend", color: "#4ECDC4" },
    { name: "Spring AI", type: "AI Framework", color: "#7B2CBF" },
    { name: "MyBatis-Plus", type: "ORM", color: "#FF6B9D" },
    { name: "Milvus", type: "Vector DB", color: "#4ECDC4" },
    { name: "Redis", type: "Cache", color: "#FFD93D" },
    { name: "React 18", type: "Frontend", color: "#4ECDC4" },
    { name: "TypeScript", type: "Language", color: "#FF6B9D" },
    { name: "Vite", type: "Build Tool", color: "#FFD93D" }
  ];

  return (
    <section id="tech" className="py-24 px-4 relative">
      <div
        className="geo-circle w-80 h-80 top-10 right-10 border-8"
        style={{ borderColor: "#FF6B9D", opacity: 0.1 }}
      ></div>

      <div className="max-w-6xl mx-auto">
        <div className="text-center mb-16">
          <h2 className="text-4xl sm:text-5xl font-black text-neo text-gray-900 dark:text-white mb-4 tracking-tighter">
            技术栈
          </h2>
          <p className="text-gray-600 dark:text-gray-400 max-w-2xl mx-auto font-medium">
            现代化技术选型，确保系统高性能、高可用、易维护
          </p>
          <div className="neo-divider w-24 mx-auto mt-4"></div>
        </div>

        <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
          {techs.map((tech, i) => (
            <div
              key={i}
              className={`neo-tech group cursor-pointer ${i % 2 === 0 ? "rotate-1" : "-rotate-1"}`}
            >
              <div className="neo-tech-icon" style={{ background: tech.color }}>
                {tech.name.charAt(0)}
              </div>
              <div className="font-black text-gray-900 dark:text-white text-center">
                {tech.name}
              </div>
              <div className="text-xs font-medium text-gray-500 dark:text-gray-400 text-center uppercase tracking-wider">
                {tech.type}
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

// 使用场景组件
function ScenariosSection() {
  const scenarios = [
    {
      icon: (
        <svg className="w-10 h-10" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4"
          />
        </svg>
      ),
      title: "企业知识库",
      desc: "构建内部知识库，员工自助查询，提升工作效率",
      color: "#FFD93D"
    },
    {
      icon: (
        <svg className="w-10 h-10" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M18.364 5.636l-3.536 3.536m0 5.656l3.536 3.536M9.172 9.172L5.636 5.636m3.536 9.192l-3.536 3.536M21 12a9 9 0 11-18 0 9 9 0 0118 0zm-5 0a4 4 0 11-8 0 4 4 0 018 0z"
          />
        </svg>
      ),
      title: "智能客服",
      desc: "7x24小时智能客服，自动回答常见问题，降低人工成本",
      color: "#FF6B9D"
    },
    {
      icon: (
        <svg className="w-10 h-10" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253"
          />
        </svg>
      ),
      title: "文档问答",
      desc: "上传文档资料，智能分析内容，快速获取关键信息",
      color: "#4ECDC4"
    },
    {
      icon: (
        <svg className="w-10 h-10" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-3 7h3m-3 4h3m-6-4h.01M9 16h.01"
          />
        </svg>
      ),
      title: "专业咨询",
      desc: "法律、医疗、金融等专业领域咨询辅助工具",
      color: "#7B2CBF"
    }
  ];

  return (
    <section id="scenarios" className="py-24 px-4 relative">
      <div
        className="geo-circle w-64 h-64 -top-20 -left-20 border-8"
        style={{ borderColor: "#FFD93D", opacity: 0.2 }}
      ></div>
      <div
        className="geo-circle w-48 h-48 bottom-10 right-10 border-6"
        style={{ borderColor: "#FF6B9D", opacity: 0.2 }}
      ></div>
      <div className="geo-wave bottom-1/3 left-0 rotate-12"></div>

      <div className="max-w-6xl mx-auto relative z-10">
        <div className="text-center mb-16">
          <h2 className="text-4xl sm:text-5xl font-black text-neo text-gray-900 dark:text-white mb-4 tracking-tighter">
            使用场景
          </h2>
          <p className="text-gray-600 dark:text-gray-300 max-w-2xl mx-auto font-medium">
            多行业多场景覆盖，满足不同业务需求
          </p>
          <div className="neo-divider w-24 mx-auto mt-4"></div>
        </div>

        <div className="grid sm:grid-cols-2 lg:grid-cols-4 gap-6">
          {scenarios.map((scenario, i) => (
            <div
              key={i}
              className={`group neo-card p-6 text-center cursor-pointer ${i % 2 === 0 ? "" : "rotate-2"}`}
            >
              <div
                className="neo-icon w-16 h-16 mx-auto mb-4 group-hover:rotate-12 transition-transform"
                style={{ background: scenario.color }}
              >
                {scenario.icon}
              </div>
              <h3 className="text-lg font-black text-gray-900 dark:text-white mb-2 tracking-tight">
                {scenario.title}
              </h3>
              <p className="text-sm text-gray-600 dark:text-gray-300 font-medium">
                {scenario.desc}
              </p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

// 页脚组件
function Footer() {
  return (
    <footer className="py-12 px-4 border-t-4 border-[#1a1a2e] bg-[#FFD93D]">
      <div className="max-w-6xl mx-auto">
        <div className="grid sm:grid-cols-3 gap-8 mb-8">
          <div>
            <div className="flex items-center space-x-3 mb-4">
              <div className="neo-logo">
                <span>R</span>
              </div>
              <span className="font-black text-gray-900">RAG 知识库</span>
            </div>
            <p className="text-sm text-gray-800 font-medium">RAG 智能问答平台，连接知识与 AI</p>
          </div>
          <div>
            <h4 className="font-black text-gray-900 mb-3">快速链接</h4>
            <div className="space-y-2 text-sm font-medium">
              <a
                href="#features"
                className="block text-gray-800 hover:text-[#FF6B9D] transition-colors"
              >
                功能特性
              </a>
              <a
                href="#tech"
                className="block text-gray-800 hover:text-[#FF6B9D] transition-colors"
              >
                技术栈
              </a>
              <a
                href="#scenarios"
                className="block text-gray-800 hover:text-[#FF6B9D] transition-colors"
              >
                使用场景
              </a>
            </div>
          </div>
          <div>
            <h4 className="font-black text-gray-900 mb-3">GitHub</h4>
            <a
              href="https://github.com/CogLear/RagKnowledgeSystem.git"
              target="_blank"
              rel="noopener noreferrer"
              className="flex items-center gap-2 text-sm font-medium text-gray-800 hover:text-[#FF6B9D] transition-colors"
            >
              <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
                <path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0024 12c0-6.63-5.37-12-12-12z"/>
              </svg>
              CogLear/RagKnowledgeSystem
            </a>
          </div>
        </div>
        <div className="pt-8 border-t-2 border-gray-900 text-center text-sm font-bold text-gray-900 uppercase tracking-wider">
          © 2026 RAG 知识库系统. All rights reserved.
        </div>
      </div>
    </footer>
  );
}

// 首页主组件
export default function LandingPage() {
  return (
    <div className="min-h-screen transition-colors duration-300 relative">
      {/* 动态背景 */}
      <div className="dynamic-bg">
        <div className="bg-dots"></div>
      </div>

      {/* 内容区域 */}
      <div className="relative z-10">
        <Navbar />
        <HeroSection />
        <AboutSection />
        <FeaturesSection />
        <TechSection />
        <ScenariosSection />
        <Footer />
      </div>
    </div>
  );
}
