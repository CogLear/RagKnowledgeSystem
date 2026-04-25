import * as React from "react";
import { Brain, Lightbulb, Send, Square } from "lucide-react";

import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";
import { useTheme } from "@/context/ThemeContext";

export function ChatInput() {
  const [value, setValue] = React.useState("");
  const [isFocused, setIsFocused] = React.useState(false);
  const isComposingRef = React.useRef(false);
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null);
  const {
    sendMessage,
    isStreaming,
    cancelGeneration,
    deepThinkingEnabled,
    setDeepThinkingEnabled,
    inputFocusKey
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

  React.useEffect(() => {
    if (!inputFocusKey) return;
    focusInput();
  }, [inputFocusKey, focusInput]);

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
    <div className="space-y-4">
      <div
        className={cn(
          "neo-card-flat relative flex flex-col p-4",
          isFocused && "ring-2 ring-[var(--neo-yellow)]"
        )}
      >
        <div className="relative">
          <Textarea
            ref={textareaRef}
            value={value}
            onChange={(event) => setValue(event.target.value)}
            placeholder={deepThinkingEnabled ? "输入需要深度分析的问题..." : "输入你的问题..."}
            className={cn(
              "neo-input max-h-40 min-h-[44px] w-full resize-none border-0 bg-transparent px-2 pt-2 pb-2 pr-2 text-[15px] shadow-none focus-visible:ring-0",
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
            aria-label="聊天输入框"
          />
        </div>
        <div className="relative mt-2 flex items-center gap-3">
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
            <Brain
              className={cn(
                "h-3.5 w-3.5",
                deepThinkingEnabled && (isDark ? "text-[var(--neo-yellow)]" : "")
              )}
            />
            <span>深度思考</span>
            {deepThinkingEnabled ? (
              <span className="neo-tag h-2 w-2 rounded-full animate-pulse" />
            ) : null}
          </button>
          {deepThinkingEnabled ? (
            <span
              className={cn(
                "flex items-center gap-1.5 text-xs",
                isDark ? "text-[var(--neo-yellow)]" : "text-[var(--neo-dark)]"
              )}
            >
              <Lightbulb className="h-3.5 w-3.5" />
              深度思考已开启
            </span>
          ) : null}
          <button
            type="button"
            onClick={handleSubmit}
            disabled={!hasContent && !isStreaming}
            aria-label={isStreaming ? "停止生成" : "发送消息"}
            className={cn(
              "ml-auto flex h-10 w-10 items-center justify-center rounded-full p-0 transition-all duration-300",
              !hasContent && !isStreaming && "opacity-50 cursor-not-allowed"
            )}
            style={
              isDark
                ? {
                    background: "linear-gradient(135deg, #667eea 0%, #764ba2 100%)",
                    color: "#ffffff"
                  }
                : {
                    background: "linear-gradient(135deg, #11998e 0%, #38ef7d 100%)",
                    color: "#ffffff"
                  }
            }
          >
            {isStreaming ? (
              <Square className="h-4 w-4" style={{ fill: "currentColor" }} />
            ) : (
              <Send className="h-4 w-4" />
            )}
          </button>
        </div>
      </div>
    </div>
  );
}
