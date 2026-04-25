import * as React from "react";
import { differenceInCalendarDays, isValid } from "date-fns";
import { Bot, MessageSquare, MoreHorizontal, Pencil, Plus, Trash2 } from "lucide-react";
import { useNavigate } from "react-router-dom";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle
} from "@/components/ui/alert-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger
} from "@/components/ui/dropdown-menu";
import { Loading } from "@/components/common/Loading";
import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";
import { useTheme } from "@/context/ThemeContext";

interface SidebarProps {
  isOpen: boolean;
  onClose: () => void;
}

export function Sidebar({ isOpen, onClose }: SidebarProps) {
  const {
    sessions,
    currentSessionId,
    isLoading,
    sessionsLoaded,
    createSession,
    deleteSession,
    renameSession,
    selectSession,
    fetchSessions
  } = useChatStore();
  const navigate = useNavigate();
  const { isDark } = useTheme();
  const [query, setQuery] = React.useState("");
  const [renamingId, setRenamingId] = React.useState<string | null>(null);
  const [renameValue, setRenameValue] = React.useState("");
  const [deleteTarget, setDeleteTarget] = React.useState<{
    id: string;
    title: string;
  } | null>(null);
  const renameInputRef = React.useRef<HTMLInputElement | null>(null);

  React.useEffect(() => {
    if (sessions.length === 0) {
      fetchSessions().catch(() => null);
    }
  }, [fetchSessions, sessions.length]);

  const filteredSessions = React.useMemo(() => {
    const keyword = query.trim().toLowerCase();
    if (!keyword) return sessions;
    return sessions.filter((session) => {
      const title = (session.title || "新对话").toLowerCase();
      return title.includes(keyword) || session.id.toLowerCase().includes(keyword);
    });
  }, [query, sessions]);

  const groupedSessions = React.useMemo(() => {
    const now = new Date();
    const groups = new Map<string, typeof filteredSessions>();
    const order: string[] = [];

    const resolveLabel = (value?: string) => {
      const parsed = value ? new Date(value) : now;
      const date = isValid(parsed) ? parsed : now;
      const diff = Math.max(0, differenceInCalendarDays(now, date));
      if (diff === 0) return "今天";
      if (diff <= 7) return "7天内";
      if (diff <= 30) return "30天内";
      return "更早";
    };

    filteredSessions.forEach((session) => {
      const label = resolveLabel(session.lastTime);
      if (!groups.has(label)) {
        groups.set(label, []);
        order.push(label);
      }
      groups.get(label)?.push(session);
    });

    return order.map((label) => ({
      label,
      items: groups.get(label) || []
    }));
  }, [filteredSessions]);

  React.useEffect(() => {
    if (renamingId) {
      renameInputRef.current?.focus();
      renameInputRef.current?.select();
    }
  }, [renamingId]);

  const sessionTitleFont =
    '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Helvetica Neue", Arial, sans-serif';

  const startRename = (id: string, title: string) => {
    setRenamingId(id);
    setRenameValue(title || "新对话");
  };

  const cancelRename = () => {
    setRenamingId(null);
    setRenameValue("");
  };

  const commitRename = async () => {
    if (!renamingId) return;
    const nextTitle = renameValue.trim();
    if (!nextTitle) {
      cancelRename();
      return;
    }
    const currentTitle = sessions.find((session) => session.id === renamingId)?.title || "新对话";
    if (nextTitle === currentTitle) {
      cancelRename();
      return;
    }
    await renameSession(renamingId, nextTitle);
    cancelRename();
  };

  return (
    <>
      <div
        className={cn(
          "fixed inset-0 z-30 backdrop-blur-sm transition-opacity lg:hidden",
          isDark ? "bg-[#1a1a2e]/70" : "bg-slate-900/30",
          isOpen ? "opacity-100" : "pointer-events-none opacity-0"
        )}
        onClick={onClose}
      />
      <aside
        className={cn(
          "fixed left-0 top-0 z-40 flex h-screen w-[260px] flex-shrink-0 flex-col transition-transform duration-300",
          isDark
            ? "bg-[#1a1a2e] border-r-4 border-[var(--neo-white)]"
            : "bg-[var(--neo-white)] border-r-4 border-[var(--neo-dark)]",
          isOpen ? "translate-x-0" : "-translate-x-full"
        )}
      >
        <div
          className={cn(
            "border-b px-4 py-4",
            isDark ? "border-white/20" : "border-[var(--neo-dark)]"
          )}
        >
          <div className="flex items-center gap-3">
            <div className={cn("neo-icon flex h-9 w-9 items-center justify-center rounded-lg")}>
              <Bot
                className={cn(
                  "h-4 w-4",
                  isDark ? "text-[var(--neo-yellow)]" : "text-[var(--neo-dark)]"
                )}
              />
            </div>
            <div>
              <p
                className={cn(
                  "text-sm font-semibold",
                  isDark ? "text-[var(--neo-white)]" : "text-[var(--neo-dark)]"
                )}
              >
                RAG 智能问答
              </p>
            </div>
          </div>
        </div>
        <div className="px-3 py-3 space-y-2">
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="搜索对话..."
            className={cn(
              "neo-input h-9 w-full rounded-lg px-3 text-sm placeholder",
              isDark
                ? "text-[var(--neo-white)] placeholder:text-white/60"
                : "text-[var(--neo-dark)]"
            )}
          />
          <button
            type="button"
            className={cn(
              "neo-btn neo-btn-sm flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left",
              isDark ? "text-white" : "neo-btn-primary"
            )}
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
            onClick={() => {
              createSession().catch(() => null);
              navigate("/chat");
              onClose();
            }}
          >
            <Plus className="h-4 w-4" />
            <span className="text-sm">新建对话</span>
          </button>
        </div>
        <div className="relative flex-1 min-h-0">
          <div className="h-full overflow-y-auto sidebar-scroll">
            {sessions.length === 0 && (!sessionsLoaded || isLoading) ? (
              <div
                className={cn(
                  "flex h-full items-center justify-center",
                  isDark ? "text-white/50" : "text-[#999999]"
                )}
                style={{ fontFamily: sessionTitleFont }}
              >
                <Loading label="加载会话中" />
              </div>
            ) : filteredSessions.length === 0 ? (
              <div
                className={cn(
                  "flex h-full flex-col items-center justify-center px-4",
                  isDark ? "text-white/30" : "text-[#999999]"
                )}
              >
                <MessageSquare className="h-10 w-10 mb-2" />
                <p className="text-[13px]">暂无对话记录</p>
              </div>
            ) : (
              <div className="px-3">
                {groupedSessions.map((group, index) => (
                  <div
                    key={group.label}
                    className={cn("flex flex-col", index === 0 ? "mt-0" : "mt-4")}
                  >
                    <p
                      className={cn(
                        "mb-1 pl-2 text-[11px] font-medium uppercase",
                        isDark ? "text-white/40" : "text-[#999999]"
                      )}
                    >
                      {group.label}
                    </p>
                    {group.items.map((session) => (
                      <div
                        key={session.id}
                        className={cn(
                          "group flex cursor-pointer items-center justify-between gap-2 rounded-lg px-3 py-2.5 text-[14px] transition-all duration-150",
                          currentSessionId === session.id
                            ? isDark
                              ? "bg-[var(--neo-yellow)]/20 text-[var(--neo-yellow)]"
                              : "bg-[var(--neo-pink)]/20 text-[var(--neo-pink)]"
                            : isDark
                              ? "text-white/60 hover:bg-white/5 hover:text-white/80"
                              : "text-[#666666] hover:bg-[#F5F5F5] hover:text-[#1A1A1A]"
                        )}
                        role="button"
                        tabIndex={0}
                        onClick={() => {
                          if (renamingId === session.id) return;
                          if (renamingId) {
                            cancelRename();
                          }
                          selectSession(session.id).catch(() => null);
                          navigate(`/chat/${session.id}`);
                        }}
                        onKeyDown={(event) => {
                          if (event.key === "Enter") {
                            selectSession(session.id).catch(() => null);
                            navigate(`/chat/${session.id}`);
                          }
                        }}
                      >
                        {renamingId === session.id ? (
                          <input
                            ref={renameInputRef}
                            value={renameValue}
                            onChange={(event) => setRenameValue(event.target.value)}
                            onClick={(event) => event.stopPropagation()}
                            onKeyDown={(event) => {
                              if (event.key === "Enter") {
                                event.preventDefault();
                                commitRename().catch(() => null);
                              }
                              if (event.key === "Escape") {
                                event.preventDefault();
                                cancelRename();
                              }
                            }}
                            onBlur={() => {
                              commitRename().catch(() => null);
                            }}
                            className={cn(
                              "neo-input h-7 flex-1 rounded-md px-2 text-[13px]",
                              isDark ? "text-[var(--neo-white)]" : "text-[var(--neo-dark)]"
                            )}
                          />
                        ) : (
                          <span className="min-w-0 flex-1 truncate font-normal">
                            {session.title || "新对话"}
                          </span>
                        )}
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild>
                            <button
                              type="button"
                              className={cn(
                                "flex h-6 w-6 items-center justify-center rounded opacity-0 transition-all duration-150 group-hover:opacity-100",
                                currentSessionId === session.id
                                  ? isDark
                                    ? "pointer-events-auto opacity-100 text-[var(--neo-yellow)]"
                                    : "pointer-events-auto opacity-100 text-[var(--neo-pink)]"
                                  : isDark
                                    ? "text-white/40 hover:bg-white/10"
                                    : "text-[#999999] hover:bg-[#F5F5F5]"
                              )}
                              onClick={(event) => event.stopPropagation()}
                              aria-label="会话操作"
                            >
                              <MoreHorizontal className="h-4 w-4" />
                            </button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent
                            align="start"
                            className={cn("neo-card min-w-[120px] rounded-lg p-1")}
                          >
                            <DropdownMenuItem
                              onClick={(event) => {
                                event.stopPropagation();
                                startRename(session.id, session.title || "新对话");
                              }}
                              className={cn(
                                "flex items-center gap-2 rounded px-3 py-2 text-[13px]",
                                isDark
                                  ? "text-[var(--neo-white)] hover:bg-white/10"
                                  : "text-[var(--neo-dark)] hover:bg-gray-100"
                              )}
                            >
                              <Pencil className="h-3.5 w-3.5" />
                              重命名
                            </DropdownMenuItem>
                            <DropdownMenuItem
                              onClick={(event) => {
                                event.stopPropagation();
                                setDeleteTarget({
                                  id: session.id,
                                  title: session.title || "新对话"
                                });
                              }}
                              className={cn(
                                "flex items-center gap-2 rounded px-3 py-2 text-[13px] hover:bg-[#FEF2F2]",
                                isDark ? "text-rose-400" : "text-[#EF4444]"
                              )}
                            >
                              <Trash2 className="h-3.5 w-3.5" />
                              删除
                            </DropdownMenuItem>
                          </DropdownMenuContent>
                        </DropdownMenu>
                      </div>
                    ))}
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </aside>
      <AlertDialog
        open={Boolean(deleteTarget)}
        onOpenChange={(open) => {
          if (!open) {
            setDeleteTarget(null);
          }
        }}
      >
        <AlertDialogContent
          className={cn("neo-card", isDark ? "bg-[#1a1a2e]" : "bg-[var(--neo-white)]")}
        >
          <AlertDialogHeader>
            <AlertDialogTitle>删除该会话？</AlertDialogTitle>
            <AlertDialogDescription>
              [{deleteTarget?.title || "该会话"}] 将被永久删除，无法恢复。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel className={cn("neo-btn", isDark ? "" : "neo-btn-primary")}>
              取消
            </AlertDialogCancel>
            <AlertDialogAction
              className={cn("neo-btn", isDark ? "neo-btn-primary" : "")}
              onClick={() => {
                if (!deleteTarget) return;
                const target = deleteTarget;
                const isCurrent = currentSessionId === target.id;
                setDeleteTarget(null);
                deleteSession(target.id)
                  .then(() => {
                    if (isCurrent) {
                      navigate("/chat");
                    }
                  })
                  .catch(() => null);
              }}
            >
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
