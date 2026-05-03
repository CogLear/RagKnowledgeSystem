import { Brain, Loader2 } from "lucide-react";

import { useChatThemeStore } from "@/stores/chatThemeStore";
import { cn } from "@/lib/utils";

interface ThinkingIndicatorProps {
  content?: string;
  duration?: number;
}

export function ThinkingIndicator({ content, duration }: ThinkingIndicatorProps) {
  const { theme } = useChatThemeStore();
  const isAurora = theme === "aurora";

  return (
    <div
      className={cn(
        "max-w-[85%] rounded-xl border-2",
        isAurora
          ? "border-purple-500/50 bg-purple-500/10"
          : "border-[var(--neo-dark)] bg-[var(--neo-white)]"
      )}
      style={{ transition: "none" }}
    >
      {/* 顶部渐变条 - 主题色 */}
      <div
        className={cn(
          "h-1.5 w-full",
          isAurora
            ? "bg-gradient-to-r from-purple-500 via-pink-500 to-blue-500"
            : "bg-gradient-to-r from-[var(--neo-yellow)] from-25% via-[var(--neo-pink)] via-50% to-[var(--neo-blue)]"
        )}
      />

      <div className="p-4">
        <div
          className={cn(
            "flex items-center gap-2",
            isAurora ? "text-purple-300" : "text-[var(--neo-dark)]"
          )}
        >
          <Loader2 className="h-4 w-4" />
          <span className="text-sm font-medium">正在深度思考...</span>
          {duration ? (
            <span
              className={cn(
                "text-xs px-2 py-0.5 rounded-full",
                isAurora
                  ? "bg-purple-500/30 text-purple-300"
                  : "bg-[var(--neo-yellow)] text-[var(--neo-dark)]"
              )}
            >
              {duration}秒
            </span>
          ) : null}
        </div>
        <div
          className={cn(
            "mt-3 flex items-start gap-2 text-sm",
            isAurora ? "text-purple-200/80" : "text-[var(--neo-dark)]/80"
          )}
        >
          <Brain
            className={cn(
              "mt-0.5 h-4 w-4 shrink-0 brain-icon",
              isAurora ? "text-purple-400" : "text-[var(--neo-pink)]"
            )}
          />
          <p className="whitespace-pre-wrap leading-relaxed">
            {content || ""}
          </p>
        </div>
      </div>
    </div>
  );
}