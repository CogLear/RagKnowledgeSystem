import * as React from "react";

import { Header } from "@/components/layout/Header";
import { Sidebar } from "@/components/layout/Sidebar";
import { cn } from "@/lib/utils";

interface MainLayoutProps {
  children: React.ReactNode;
}

export function MainLayout({ children }: MainLayoutProps) {
  const [sidebarOpen, setSidebarOpen] = React.useState(true);

  return (
    <div className="relative flex min-h-screen w-full">
      <Sidebar isOpen={sidebarOpen} onClose={() => setSidebarOpen(false)} />
      <div
        className={cn(
          "relative flex min-h-screen flex-1 flex-col transition-all duration-300",
          sidebarOpen ? "lg:ml-[260px]" : "ml-0"
        )}
      >
        <div className="dynamic-bg">
          <div className="bg-dots" />
          {/* Geometric decorations */}
          <div
            className="geo-circle"
            style={{ borderColor: "var(--neo-yellow)", top: "15%", left: "5%" }}
          />
          <div
            className="geo-circle"
            style={{ borderColor: "var(--neo-pink)", top: "60%", right: "10%" }}
          />
          <div className="geo-wave" style={{ top: "40%", left: "60%" }} />
        </div>
        <div className="relative z-10 flex flex-col min-h-screen">
          <Header onToggleSidebar={() => setSidebarOpen((prev) => !prev)} />
          <main className="flex-1 min-h-0 overflow-hidden">{children}</main>
        </div>
      </div>
    </div>
  );
}
