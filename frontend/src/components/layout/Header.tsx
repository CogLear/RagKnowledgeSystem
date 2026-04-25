import * as React from "react";
import { LogOut, Menu, Moon, Sun as SunIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger
} from "@/components/ui/dropdown-menu";
import { useAuthStore } from "@/stores/authStore";
import { useChatStore } from "@/stores/chatStore";
import { useTheme } from "@/context/ThemeContext";
import { cn } from "@/lib/utils";

interface HeaderProps {
  onToggleSidebar: () => void;
}

export function Header({ onToggleSidebar }: HeaderProps) {
  const { currentSessionId, sessions } = useChatStore();
  const { user, logout } = useAuthStore();
  const { isDark, toggleTheme } = useTheme();
  const [avatarFailed, setAvatarFailed] = React.useState(false);
  const currentSession = React.useMemo(
    () => sessions.find((session) => session.id === currentSessionId),
    [sessions, currentSessionId]
  );

  const avatarUrl = user?.avatar?.trim();
  const showAvatar = Boolean(avatarUrl) && !avatarFailed;
  const avatarFallback = (user?.username || user?.userId || "用户").slice(0, 1).toUpperCase();

  return (
    <header className="nav-neo sticky top-0 z-20">
      <div className="flex h-14 items-center justify-between px-5">
        <div className="flex items-center gap-3">
          <Button
            variant="ghost"
            size="icon"
            onClick={onToggleSidebar}
            aria-label="切换侧边栏"
            className={
              isDark
                ? "text-white/80 hover:bg-white/10 hover:text-white"
                : "text-gray-600 hover:bg-gray-100 hover:text-gray-900"
            }
          >
            <Menu className="h-[18px] w-[18px]" />
          </Button>
          <h1
            className={`text-[15px] font-medium tracking-wide ${isDark ? "text-white" : "text-gray-800"}`}
          >
            {currentSession?.title || "新对话"}
          </h1>
        </div>
        <div className="flex items-center gap-4">
          <button
            type="button"
            onClick={toggleTheme}
            aria-label="切换主题"
            className={cn("neo-btn neo-btn-sm", isDark ? "text-white" : "neo-btn-primary")}
            style={isDark ? { backgroundColor: "var(--neo-purple)" } : undefined}
            onMouseEnter={
              isDark
                ? (e) => (e.currentTarget.style.backgroundColor = "var(--neo-pink)")
                : undefined
            }
            onMouseLeave={
              isDark
                ? (e) => (e.currentTarget.style.backgroundColor = "var(--neo-purple)")
                : undefined
            }
          >
            {isDark ? <Moon className="h-4 w-4" /> : <SunIcon className="h-4 w-4" />}
          </button>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  className={cn(
                    "neo-icon flex h-9 w-9 items-center justify-center overflow-hidden rounded-lg transition-all duration-200",
                    isDark ? "bg-[var(--neo-yellow)]" : "bg-[var(--neo-pink)]"
                  )}
                  aria-label="用户菜单"
                >
                  {showAvatar ? (
                    <img
                      src={avatarUrl}
                      alt={user?.username || user?.userId || "用户"}
                      className="h-full w-full object-cover"
                      onError={() => setAvatarFailed(true)}
                    />
                  ) : (
                    <span
                      className={cn(
                        "text-sm font-medium",
                        isDark ? "text-[var(--neo-dark)]" : "text-white"
                      )}
                    >
                      {avatarFallback}
                    </span>
                  )}
                </button>
                <span
                  className={cn(
                    "text-sm font-semibold truncate max-w-[100px]",
                    isDark ? "text-[var(--neo-yellow)]" : "text-[var(--neo-dark)]"
                  )}
                >
                  {user?.username || user?.userId || "用户"}
                </span>
              </div>
            </DropdownMenuTrigger>
            <DropdownMenuContent
              align="center"
              sideOffset={8}
              className={cn(
                "min-w-[140px] overflow-hidden rounded-lg border-4 p-1.5",
                isDark
                  ? "bg-[#1a1a2e] border-[var(--neo-white)]"
                  : "bg-[var(--neo-white)] border-[var(--neo-dark)]"
              )}
            >
              <DropdownMenuItem
                onClick={() => logout()}
                className={cn(
                  "flex cursor-pointer items-center gap-3 rounded-lg px-3 py-2.5 text-sm transition-all duration-150",
                  isDark
                    ? "text-[var(--neo-white)] hover:bg-white/10"
                    : "text-[var(--neo-dark)] hover:bg-gray-100"
                )}
              >
                <LogOut className="h-4 w-4 opacity-60" />
                <span className="font-normal">退出登录</span>
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </div>
    </header>
  );
}
