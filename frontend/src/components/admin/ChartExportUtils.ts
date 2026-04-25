// frontend/src/components/admin/ChartExportUtils.ts

/**
 * 导出图表数据为 CSV 文件
 */
export function exportChartToCSV(
  data: Array<{ name: string; data: Array<{ ts: number; value: number }> }>,
  title: string,
  timeWindow: string
): void {
  if (!data || data.length === 0) return;

  // 构建 CSV 内容
  const headers = ["时间", ...data.map((s) => s.name)].join(",");
  const timestampSet = new Set<number>();
  data.forEach((series) => series.data.forEach((p) => timestampSet.add(p.ts)));
  const timestamps = Array.from(timestampSet).sort((a, b) => a - b);

  const rows = timestamps.map((ts) => {
    const dateStr = new Date(ts).toLocaleString("zh-CN");
    const values = data.map((series) => {
      const point = series.data.find((p) => p.ts === ts);
      return point?.value ?? "";
    });
    return [dateStr, ...values].join(",");
  });

  const csvContent = [headers, ...rows].join("\n");
  downloadFile(csvContent, `${title}_${timeWindow}_${formatDateForFile()}.csv`, "text/csv");
}

/**
 * 导出图表容器为 PNG 图片
 */
export async function exportChartToPNG(
  containerRef: React.RefObject<HTMLElement>,
  title: string,
  timeWindow: string
): Promise<void> {
  if (!containerRef.current) return;

  const svgEl = containerRef.current.querySelector("svg");
  if (!svgEl) return;

  const svgRect = svgEl.getBoundingClientRect();
  const svgWidth = svgRect.width * 2; // 2x 分辨率
  const svgHeight = svgRect.height * 2;

  // Clone and inline SVG resources to avoid tainted canvas
  const clonedSvg = svgEl.cloneNode(true) as SVGSVGElement;

  // Inline all external resources (images, etc.)
  const images = clonedSvg.querySelectorAll("image");
  images.forEach((img) => {
    const href = img.getAttribute("href");
    if (href && !href.startsWith("data:")) {
      // Can't inline external images, just remove them
      img.removeAttribute("href");
    }
  });

  const svgData = new XMLSerializer().serializeToString(clonedSvg);
  const svgBlob = new Blob([svgData], { type: "image/svg+xml;charset=utf-8" });
  const svgUrl = URL.createObjectURL(svgBlob);

  const canvas = document.createElement("canvas");
  canvas.width = svgWidth;
  canvas.height = svgHeight;
  const ctx = canvas.getContext("2d");
  if (!ctx) {
    URL.revokeObjectURL(svgUrl);
    return;
  }

  const img = new Image();

  return new Promise((resolve, reject) => {
    img.onload = () => {
      try {
        ctx.fillStyle = "#ffffff";
        ctx.fillRect(0, 0, svgWidth, svgHeight);
        ctx.drawImage(img, 0, 0, svgWidth, svgHeight);
        URL.revokeObjectURL(svgUrl);

        canvas.toBlob((blob) => {
          if (blob) {
            downloadFile(blob, `${title}_${timeWindow}_${formatDateForFile()}.png`, "image/png");
            resolve();
          } else {
            reject(new Error("Failed to create PNG blob"));
          }
        }, "image/png");
      } catch (e) {
        URL.revokeObjectURL(svgUrl);
        reject(e);
      }
    };

    img.onerror = () => {
      URL.revokeObjectURL(svgUrl);
      reject(new Error("Failed to load SVG image"));
    };

    img.src = svgUrl;
  });
}

function downloadFile(content: string | Blob, filename: string, mimeType: string): void {
  const blob = content instanceof Blob ? content : new Blob([content], { type: mimeType });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}

function formatDateForFile(): string {
  return new Date()
    .toISOString()
    .slice(0, 19)
    .replace(/[T:]/g, "-")
    .replace(/^(\d{4})-(\d{2})-(\d{2})-(.+)$/, "$1$2$3_$4");
}
