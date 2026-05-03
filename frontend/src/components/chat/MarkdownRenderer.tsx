import * as React from "react";
import Markdown from "markdown-to-jsx";
import { Check, Copy, ImageIcon } from "lucide-react";
import { Prism as SyntaxHighlighter } from "react-syntax-highlighter";
import { oneDark, oneLight } from "react-syntax-highlighter/dist/esm/styles/prism";
import DOMPurify from "dompurify";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { useThemeStore } from "@/stores/themeStore";

interface MarkdownRendererProps {
  content: string;
}

export function MarkdownRenderer({ content }: MarkdownRendererProps) {
  const theme = useThemeStore((state) => state.theme);

  return (
    <Markdown
      options={{
        overrides: {
          code({ children, className, ...props }) {
            const match = /language-(\w+)/.exec(className || "");
            const rawLanguage = match?.[1] || "text";
            const language = DOMPurify.sanitize(rawLanguage, { ALLOWED_TAGS: [], ALLOWED_ATTR: [] });
            const value = String(children).replace(/\n$/, "");

            const safeClassName = className
              ? DOMPurify.sanitize(className, { ALLOWED_TAGS: [], ALLOWED_ATTR: [] })
              : "";

            // 判断是否为内联代码
            if (!value.includes("\n")) {
              return (
                <code
                  className={cn(
                    "rounded px-1.5 py-0.5 text-[13px] font-mono bg-[#f6f8fa] text-[#24292f]",
                    "dark:bg-[#161b22] dark:text-[#c9d1d9]",
                    safeClassName
                  )}
                  {...props}
                >
                  {children}
                </code>
              );
            }

            return (
              <div className="my-3 overflow-hidden rounded-md border border-[#d0d7de] bg-[#f6f8fa] dark:border-[#30363d] dark:bg-[#161b22]">
                <div className="flex items-center justify-between border-b border-[#d0d7de] bg-[#f6f8fa] px-3 py-1.5 dark:border-[#30363d] dark:bg-[#161b22]">
                  <span className="font-mono text-[11px] font-semibold uppercase tracking-wider text-[#57606a] dark:text-[#8b949e]">
                    {language}
                  </span>
                  <CopyButton value={value} />
                </div>
                <div className="overflow-x-auto">
                  <SyntaxHighlighter
                    language={language}
                    style={theme === "dark" ? oneDark : oneLight}
                    PreTag="div"
                    customStyle={{
                      margin: 0,
                      padding: "0.75rem 1rem",
                      background: "transparent",
                      fontSize: "13px",
                      lineHeight: "1.5"
                    }}
                    showLineNumbers={false}
                    wrapLines={true}
                  >
                    {value}
                  </SyntaxHighlighter>
                </div>
              </div>
            );
          },
          img({ src, alt, ...props }) {
            const [hasError, setHasError] = React.useState(false);

            const safeSrc = src
              ? DOMPurify.sanitize(src, { ALLOWED_URI_REGEXP: /^(https?:|data:|blob:)/ })
              : "";

            if (hasError || !safeSrc) {
              return (
                <div className="my-3 flex items-center gap-2 text-sm text-[#999999]">
                  <ImageIcon className="h-4 w-4" />
                  <span>图片加载失败</span>
                </div>
              );
            }

            return (
              <img
                src={safeSrc}
                alt=""
                className="my-3 max-w-full rounded-lg"
                onError={() => setHasError(true)}
                loading="lazy"
                {...props}
              />
            );
          },
          a({ children, href, ...props }) {
            const safeHref = href
              ? DOMPurify.sanitize(href, { ALLOWED_URI_REGEXP: /^(https?:|mailto:)/ })
              : "#";
            return (
              <a
                className="text-[#0969da] underline-offset-4 hover:underline dark:text-[#58a6ff]"
                href={safeHref}
                target="_blank"
                rel="noopener noreferrer"
                {...props}
              >
                {children}
              </a>
            );
          },
          table({ children, ...props }) {
            return (
              <div className="overflow-x-auto">
                <table
                  className="w-full border-collapse border border-[#d0d7de] rounded-md dark:border-[#30363d]"
                  {...props}
                >
                  {children}
                </table>
              </div>
            );
          },
          blockquote({ children, ...props }) {
            return (
              <blockquote
                className="my-3 border-l-4 border-[#3B82F6] bg-[#F0F7FF] pl-3 pr-3 py-2 italic text-[#333333] dark:border-[#60A5FA] dark:bg-[#1A2332] dark:text-[#CCCCCC]"
                {...props}
              >
                {children}
              </blockquote>
            );
          }
        }
      }}
      className="prose prose-gray max-w-none dark:prose-invert dark:text-gray-200"
    >
      {content}
    </Markdown>
  );
}

function CopyButton({ value }: { value: string }) {
  const [copied, setCopied] = React.useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      setCopied(false);
    }
  };

  return (
    <Button
      variant="ghost"
      size="icon"
      onClick={handleCopy}
      aria-label="复制代码"
      className="h-7 w-7 hover:bg-[#eaeef2] dark:hover:bg-[#30363d] transition-colors"
    >
      {copied ? (
        <Check className="h-3.5 w-3.5 text-green-600 dark:text-green-400" />
      ) : (
        <Copy className="h-3.5 w-3.5 text-[#57606a] dark:text-[#8b949e]" />
      )}
    </Button>
  );
}