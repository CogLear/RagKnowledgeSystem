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
        "rounded-lg border p-4",
        isAurora ? "border-purple-500/30 bg-purple-500/10" : "border-[#BFDBFE] bg-[#DBEAFE]"
      )}
    >
      <div
        className={cn("flex items-center gap-2", isAurora ? "text-purple-300" : "text-[#2563EB]")}
      >
        <Loader2 className="h-4 w-4 animate-spin" />
        <span className="text-sm font-medium">正在深度思考...</span>
        {duration ? (
          <span
            className={cn(
              "text-xs px-2 py-0.5 rounded-full",
              isAurora ? "bg-purple-500/30 text-purple-300" : "bg-[#BFDBFE] text-[#2563EB]"
            )}
          >
            {duration}秒
          </span>
        ) : null}
      </div>
      <div
        className={cn(
          "mt-3 flex items-start gap-2 text-sm",
          isAurora ? "text-purple-200/80" : "text-[#1E40AF]"
        )}
      >
        <Brain
          className={cn("mt-0.5 h-4 w-4 shrink-0", isAurora ? "text-purple-400" : "text-[#2563EB]")}
        />
        <p className="whitespace-pre-wrap leading-relaxed">
          {content || ""}
          <span
            className={cn(
              "ml-1 inline-block h-4 w-1.5 animate-pulse align-middle",
              isAurora ? "bg-purple-400" : "bg-[#3B82F6]"
            )}
          />
        </p>
      </div>
    </div>
  );
}
