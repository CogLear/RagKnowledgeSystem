import * as React from "react";
import { Eye, EyeOff, User } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { useTheme } from "@/context/ThemeContext";
import { useAuthStore } from "@/stores/authStore";

// Logo component
function NeoLogo() {
  return (
    <div className="neo-logo">
      <span>R</span>
    </div>
  );
}

// Neo-style input component
function NeoInput({
  type = "text",
  placeholder,
  value,
  onChange,
  autoComplete,
  icon: Icon,
  showPasswordToggle,
  showPassword,
  onTogglePassword
}: {
  type?: string;
  placeholder: string;
  value: string;
  onChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
  autoComplete?: string;
  icon: React.ComponentType<{ className?: string }>;
  showPasswordToggle?: boolean;
  showPassword?: boolean;
  onTogglePassword?: () => void;
}) {
  return (
    <div className="relative">
      <div className="absolute left-4 top-1/2 -translate-y-1/2 text-neo-dark opacity-60">
        <Icon className="w-5 h-5" />
      </div>
      <input
        type={type}
        placeholder={placeholder}
        value={value}
        onChange={onChange}
        autoComplete={autoComplete}
        className="neo-input"
      />
      {showPasswordToggle && onTogglePassword && (
        <button
          type="button"
          onClick={onTogglePassword}
          className="absolute right-4 top-1/2 -translate-y-1/2 text-neo-dark opacity-60 hover:opacity-100 transition-opacity"
          aria-label={showPassword ? "隐藏密码" : "显示密码"}
        >
          {showPassword ? <EyeOff className="w-5 h-5" /> : <Eye className="w-5 h-5" />}
        </button>
      )}
    </div>
  );
}

export function AdminLoginPage() {
  const navigate = useNavigate();
  const { isDark } = useTheme();
  const { login, isLoading } = useAuthStore();
  const [showPassword, setShowPassword] = React.useState(false);
  const [form, setForm] = React.useState({ username: "admin", password: "admin" });
  const [error, setError] = React.useState<string | null>(null);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    if (!form.username.trim() || !form.password.trim()) {
      setError("请输入用户名和密码。");
      return;
    }
    try {
      await login(form.username.trim(), form.password.trim());
      navigate("/admin");
    } catch (err) {
      setError((err as Error).message || "登录失败，请稍后重试。");
    }
  };

  return (
    <div className="relative flex min-h-screen items-center justify-center px-4">
      {/* 动态背景 */}
      <div className="dynamic-bg">
        <div className="bg-dots"></div>
      </div>

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

      <div className="relative z-10 w-full max-w-md">
        {/* 标题区域 */}
        <div className="text-center mb-8">
          <div className="flex justify-center mb-4">
            <NeoLogo />
          </div>
          <h1 className="text-4xl font-black text-neo text-gray-900 dark:text-white mb-2 tracking-tighter">
            RAG 知识库
          </h1>
          <p className="text-gray-600 dark:text-gray-400 font-medium">管理后台</p>
        </div>

        {/* 登录卡片 */}
        <div className="neo-card p-8">
          <div className="mb-6">
            <h2 className="text-2xl font-black text-gray-900 dark:text-white tracking-tight">
              管理员登录
            </h2>
            <p className="text-sm text-gray-500 dark:text-gray-400 mt-1 font-medium">
              请使用管理员账号登录
            </p>
          </div>

          <form className="space-y-5" onSubmit={handleSubmit}>
            {/* 用户名输入 */}
            <div className="space-y-2">
              <label className="text-xs font-black uppercase tracking-wider text-gray-600 dark:text-gray-400">
                用户名
              </label>
              <NeoInput
                type="text"
                placeholder="请输入用户名"
                value={form.username}
                onChange={(e) => setForm((prev) => ({ ...prev, username: e.target.value }))}
                autoComplete="username"
                icon={User}
              />
            </div>

            {/* 密码输入 */}
            <div className="space-y-2">
              <label className="text-xs font-black uppercase tracking-wider text-gray-600 dark:text-gray-400">
                密码
              </label>
              <NeoInput
                type={showPassword ? "text" : "password"}
                placeholder="请输入密码"
                value={form.password}
                onChange={(e) => setForm((prev) => ({ ...prev, password: e.target.value }))}
                autoComplete="current-password"
                icon={User}
                showPasswordToggle
                showPassword={showPassword}
                onTogglePassword={() => setShowPassword((prev) => !prev)}
              />
            </div>

            {/* 错误提示 */}
            {error && <div className="neo-error">{error}</div>}

            {/* 提交按钮 */}
            <button
              type="submit"
              className="neo-btn neo-btn-primary w-full cursor-pointer"
              disabled={isLoading}
            >
              {isLoading ? (
                <span className="flex items-center justify-center gap-2">
                  <svg className="animate-spin w-5 h-5" viewBox="0 0 24 24" fill="none">
                    <circle
                      className="opacity-25"
                      cx="12"
                      cy="12"
                      r="10"
                      stroke="currentColor"
                      strokeWidth="4"
                    />
                    <path
                      className="opacity-75"
                      fill="currentColor"
                      d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
                    />
                  </svg>
                  登录中...
                </span>
              ) : (
                <span className="flex items-center justify-center gap-2">
                  登录
                  <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M9 5l7 7-7 7"
                    />
                  </svg>
                </span>
              )}
            </button>
          </form>
        </div>

        {/* 返回首页 */}
        <div className="text-center mt-6">
          <button
            onClick={() => navigate("/")}
            className="text-sm font-medium text-gray-500 dark:text-gray-400 hover:text-[#FF6B9D] transition-colors"
          >
            ← 返回首页
          </button>
        </div>
      </div>
    </div>
  );
}
