import { RouterProvider } from "react-router-dom";

import { ErrorBoundary } from "@/components/common/ErrorBoundary";
import { Toast } from "@/components/common/Toast";
import { router } from "@/router";
import { ThemeProvider } from "@/context/ThemeContext";

export default function App() {
  return (
    <ErrorBoundary>
      <ThemeProvider>
        <RouterProvider router={router} />
        <Toast />
      </ThemeProvider>
    </ErrorBoundary>
  );
}
