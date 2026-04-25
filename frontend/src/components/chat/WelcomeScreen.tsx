import * as React from "react";
import { Bot, Brain, Lightbulb, Send, Square } from "lucide-react";

import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";
import { useTheme } from "@/context/ThemeContext";

export function WelcomeScreen() {
  const [value, setValue] = React.useState("");
  const [isFocused, setIsFocused] = React.useState(false);
  const isComposingRef = React.useRef(false);
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null);
  const {
    sendMessage,
    isStreaming,
    cancelGeneration,
    deepThinkingEnabled,
    setDeepThinkingEnabled
  } = useChatStore();
  const { isDark } = useTheme();

  const focusInput = React.useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.focus({ preventScroll: true });
  }, []);

  const adjustHeight = React.useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    const next = Math.min(el.scrollHeight, 160);
    el.style.height = `${next}px`;
  }, []);

  React.useEffect(() => {
    adjustHeight();
  }, [value, adjustHeight]);

  const handleSubmit = async () => {
    if (isStreaming) {
      cancelGeneration();
      focusInput();
      return;
    }
    if (!value.trim()) return;
    const next = value;
    setValue("");
    focusInput();
    await sendMessage(next);
    focusInput();
  };

  const hasContent = value.trim().length > 0;

  return (
    <div className="relative flex min-h-full items-center justify-center overflow-hidden px-4 py-16 sm:px-6">
      <div className="relative w-full max-w-[860px]">
        <div
          className="text-center opacity-0 animate-fade-up"
          style={{ animationFillMode: "both" }}
        >
          <span
            className={cn(
              "neo-tag inline-flex items-center gap-2 text-xs font-medium",
              isDark ? "neo-tag-dark" : ""
            )}
          >
            <Bot className="h-3.5 w-3.5" />
            RAG 智能问答
          </span>
          <h1
            className={cn(
              "mt-4 font-display text-4xl leading-tight tracking-tight sm:text-5xl md:text-6xl",
              isDark ? "text-[var(--neo-white)]" : "text-[var(--neo-dark)]"
            )}
          >
            <span
              className={cn(
                "bg-clip-text text-transparent",
                isDark
                  ? "bg-gradient-to-r from-[var(--neo-yellow)] to-[var(--neo-pink)]"
                  : "bg-gradient-to-r from-[var(--neo-pink)] to-[var(--neo-purple)]"
              )}
            >
              RAG
            </span>
            让答案更精准
          </h1>
          <p
            className={cn("mt-4 text-base sm:text-lg", isDark ? "text-white/70" : "text-[#4B5563]")}
          >
            输入问题，AI 帮你分析
          </p>
        </div>

        <div
          className="mt-10 opacity-0 animate-fade-up"
          style={{ animationDelay: "80ms", animationFillMode: "both" }}
        >
          <div
            className={cn(
              "neo-card-flat relative flex flex-col p-5 transition-all duration-200",
              isFocused && "ring-2 ring-[var(--neo-yellow)]"
            )}
          >
            <div className="relative">
              <textarea
                ref={textareaRef}
                value={value}
                onChange={(event) => setValue(event.target.value)}
                placeholder={deepThinkingEnabled ? "输入需要深度分析的问题..." : "输入你的问题..."}
                className={cn(
                  "neo-input max-h-40 min-h-[52px] w-full resize-none border-0 bg-transparent px-2 pt-2 pb-2 text-[15px] placeholder focus:outline-none sm:text-base",
                  isDark ? "text-[var(--neo-white)]" : "text-[var(--neo-dark)]"
                )}
                rows={1}
                onFocus={() => setIsFocused(true)}
                onBlur={() => setIsFocused(false)}
                onCompositionStart={() => {
                  isComposingRef.current = true;
                }}
                onCompositionEnd={() => {
                  isComposingRef.current = false;
                }}
                onKeyDown={(event) => {
                  if (event.key === "Enter" && !event.shiftKey) {
                    const nativeEvent = event.nativeEvent as KeyboardEvent;
                    if (
                      nativeEvent.isComposing ||
                      isComposingRef.current ||
                      nativeEvent.keyCode === 229
                    ) {
                      return;
                    }
                    event.preventDefault();
                    handleSubmit();
                  }
                }}
                aria-label="发送消息"
              />
            </div>
            <div className="mt-3 flex flex-wrap items-center gap-3">
              <button
                type="button"
                onClick={() => setDeepThinkingEnabled(!deepThinkingEnabled)}
                disabled={isStreaming}
                aria-pressed={deepThinkingEnabled}
                className={cn(
                  "neo-btn neo-btn-sm flex items-center gap-2",
                  deepThinkingEnabled ? (isDark ? "" : "neo-btn-primary") : "",
                  isStreaming && "cursor-not-allowed opacity-60"
                )}
              >
                <span className="inline-flex items-center gap-2">
                  <Brain
                    className={cn(
                      "h-3.5 w-3.5",
                      deepThinkingEnabled && (isDark ? "text-[var(--neo-yellow)]" : "")
                    )}
                  />
                  深度思考
                  {deepThinkingEnabled ? (
                    <span className="neo-tag h-2 w-2 rounded-full animate-pulse" />
                  ) : null}
                </span>
              </button>
              <button
                type="button"
                onClick={handleSubmit}
                disabled={!hasContent && !isStreaming}
                aria-label={isStreaming ? "停止生成" : "发送消息"}
                className={cn(
                  "neo-btn neo-btn-primary ml-auto flex h-10 w-10 items-center justify-center rounded-full p-0",
                  !hasContent && !isStreaming && "opacity-50 cursor-not-allowed"
                )}
              >
                {isStreaming ? <Square className="h-4 w-4" /> : <Send className="h-4 w-4" />}
              </button>
            </div>
          </div>
          {deepThinkingEnabled ? (
            <p
              className={cn(
                "mt-3 text-xs",
                isDark ? "text-[var(--neo-yellow)]" : "text-[var(--neo-dark)]"
              )}
            >
              <span className="inline-flex items-center gap-1.5">
                <Lightbulb className="h-3.5 w-3.5" />
                深度思考已开启
              </span>
            </p>
          ) : null}
        </div>
      </div>
    </div>
  );
}
