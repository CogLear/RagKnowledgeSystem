import { useEffect, type ReactNode, type RefObject, useRef, useState, useCallback } from "react";
import { Download } from "lucide-react";
import { cn } from "@/lib/utils";
import { exportChartToCSV, exportChartToPNG } from "./ChartExportUtils";
import type { TrendSeries } from "./SimpleLineChart";

type ExportFormat = "csv" | "png";

interface InteractiveChartProps {
  title: string;
  timeWindow: string;
  series?: TrendSeries[];
  loading?: boolean;
  children: ReactNode;
  chartRef?: RefObject<HTMLElement>;
  className?: string;
}

export function InteractiveChart({
  title,
  timeWindow,
  series = [],
  loading = false,
  children,
  chartRef,
  className
}: InteractiveChartProps) {
  const [showExportMenu, setShowExportMenu] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const activeRef = chartRef ?? containerRef;

  const handleExport = useCallback(
    async (format: ExportFormat) => {
      setShowExportMenu(false);
      if (format === "csv") {
        exportChartToCSV(series, title, timeWindow);
      } else {
        await exportChartToPNG(activeRef as RefObject<HTMLElement>, title, timeWindow);
      }
    },
    [series, title, timeWindow, activeRef]
  );

  useEffect(() => {
    if (!showExportMenu) return;

    const handleClickOutside = (e: MouseEvent) => {
      const target = e.target as HTMLElement;
      const isInsideExportArea = target.closest("[data-export-area]");
      if (!isInsideExportArea) {
        setShowExportMenu(false);
      }
    };

    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, [showExportMenu]);

  return (
    <div
      ref={(el) => {
        containerRef.current = el;
        if (typeof chartRef === "function") {
          chartRef(el);
        } else if (chartRef) {
          (chartRef as React.MutableRefObject<HTMLElement | null>).current = el;
        }
      }}
      className={cn("relative group", className)}
    >
      {/* 工具栏 */}
      <div className="absolute right-2 top-2 z-10 flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
        {/* 导出菜单 */}
        <div className="relative" data-export-area>
          <button
            type="button"
            onClick={() => setShowExportMenu((v) => !v)}
            className="flex h-7 w-7 items-center justify-center rounded-md bg-white/90 shadow-sm hover:bg-white transition-colors"
            title="导出"
          >
            <Download className="h-3.5 w-3.5 text-slate-600" />
          </button>
          {showExportMenu && (
            <div className="absolute right-0 top-full mt-1 w-28 rounded-lg bg-white shadow-lg border border-slate-200 py-1 z-20">
              <button
                type="button"
                onClick={() => handleExport("csv")}
                className="w-full px-3 py-1.5 text-left text-xs text-slate-700 hover:bg-slate-50"
              >
                导出 CSV
              </button>
              <button
                type="button"
                onClick={() => handleExport("png")}
                className="w-full px-3 py-1.5 text-left text-xs text-slate-700 hover:bg-slate-50"
              >
                导出 PNG
              </button>
            </div>
          )}
        </div>
      </div>

      {/* Loading 骨架屏 */}
      {loading && (
        <div className="absolute inset-0 z-10 flex items-center justify-center bg-white/80 rounded-xl">
          <div className="h-4 w-4 animate-spin rounded-full border-2 border-slate-200 border-t-blue-500" />
        </div>
      )}

      {/* 图表内容 */}
      <div className={cn(loading && "opacity-50")}>{children}</div>
    </div>
  );
}
