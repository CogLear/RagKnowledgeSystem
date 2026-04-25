import * as React from "react";
import { Brain, ChevronDown } from "lucide-react";

import { FeedbackButtons } from "@/components/chat/FeedbackButtons";
import { MarkdownRenderer } from "@/components/chat/MarkdownRenderer";
import { ThinkingIndicator } from "@/components/chat/ThinkingIndicator";
import { cn } from "@/lib/utils";
import { useTheme } from "@/context/ThemeContext";
import type { Message } from "@/types";

interface MessageItemProps {
  message: Message;
  isLast?: boolean;
}

export const MessageItem = React.memo(function MessageItem({ message, isLast }: MessageItemProps) {
  const { isDark } = useTheme();
  const isUser = message.role === "user";
  const showFeedback =
    message.role === "assistant" &&
    message.status !== "streaming" &&
    message.id &&
    !message.id.startsWith("assistant-");
  const isThinking = Boolean(message.isThinking);
  const [thinkingExpanded, setThinkingExpanded] = React.useState(false);
  const hasThinking = Boolean(message.thinking && message.thinking.trim().length > 0);
  const hasContent = message.content.trim().length > 0;
  const isWaiting = message.status === "streaming" && !isThinking && !hasContent;

  if (isUser) {
    return (
      <div className="flex justify-end">
        <div
          className={cn(
            "neo-chat-user rounded-2xl px-4 py-3 max-w-[80%]",
            isDark ? "border-[var(--neo-white)]" : "border-[var(--neo-dark)]"
          )}
        >
          <p className="whitespace-pre-wrap break-words">{message.content}</p>
        </div>
      </div>
    );
  }

  const thinkingDuration = message.thinkingDuration ? `${message.thinkingDuration}秒` : "";
  return (
    <div className="group flex">
      <div className="min-w-0 flex-1 space-y-4">
        {isThinking ? (
          <ThinkingIndicator content={message.thinking} duration={message.thinkingDuration} />
        ) : null}
        {!isThinking && hasThinking ? (
          <div
            className={cn(
              "neo-card overflow-hidden",
              isDark ? "border-[var(--neo-white)]" : "border-[var(--neo-dark)]"
            )}
          >
            <button
              type="button"
              onClick={() => setThinkingExpanded((prev) => !prev)}
              className={cn(
                "flex w-full items-center gap-2 px-4 py-3 text-left transition-colors",
                isDark ? "hover:bg-white/5" : "hover:bg-gray-50"
              )}
            >
              <div
                className={cn(
                  "flex flex-1 items-center gap-2",
                  isDark ? "text-[var(--neo-yellow)]" : "text-[var(--neo-dark)]"
                )}
              >
                <div
                  className={cn(
                    "neo-icon flex h-7 w-7 items-center justify-center rounded-lg",
                    isDark ? "" : ""
                  )}
                >
                  <Brain
                    className={cn(
                      "h-4 w-4",
                      isDark ? "text-[var(--neo-yellow)]" : "text-[var(--neo-dark)]"
                    )}
                  />
                </div>
                <span className="text-sm font-medium">深度思考</span>
                {thinkingDuration ? (
                  <span
                    className={cn("neo-tag rounded-full px-2 py-0.5 text-xs", isDark ? "" : "")}
                  >
                    {thinkingDuration}
                  </span>
                ) : null}
              </div>
              <ChevronDown
                className={cn(
                  "h-4 w-4 transition-transform",
                  isDark ? "text-[var(--neo-yellow)]" : "text-[var(--neo-dark)]",
                  thinkingExpanded && "rotate-180"
                )}
              />
            </button>
            {thinkingExpanded ? (
              <div
                className={cn(
                  "border-t px-4 pb-4",
                  isDark ? "border-white/20" : "border-[var(--neo-dark)]"
                )}
              >
                <div
                  className={cn(
                    "mt-3 whitespace-pre-wrap text-sm leading-relaxed",
                    isDark ? "text-white/80" : "text-[var(--neo-dark)]"
                  )}
                >
                  {message.thinking}
                </div>
              </div>
            ) : null}
          </div>
        ) : null}
        <div className="space-y-2">
          {isWaiting ? (
            <div className="ai-wait" aria-label="思考中">
              <span className="ai-wait-dots" aria-hidden="true">
                <span className="ai-wait-dot" />
                <span className="ai-wait-dot" />
                <span className="ai-wait-dot" />
              </span>
            </div>
          ) : null}
          {hasContent ? (
            <div className="ai-message">
              <MarkdownRenderer content={message.content} />
            </div>
          ) : null}
          {message.status === "error" ? (
            <p className="text-xs text-rose-500">生成已中断。</p>
          ) : null}
          {showFeedback ? (
            <FeedbackButtons
              messageId={message.id}
              feedback={message.feedback ?? null}
              content={message.content}
              alwaysVisible={Boolean(isLast)}
            />
          ) : null}
        </div>
      </div>
    </div>
  );
});
