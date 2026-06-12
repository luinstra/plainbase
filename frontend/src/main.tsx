import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { ApiError } from "./api/client";
import { createAppRouter } from "./router";
import { applyTheme, resolveTheme } from "./theme";
import "./styles/app.css";

// Re-assert the bootstrap theme (index.html applied it pre-paint) so React-side state agrees.
applyTheme(resolveTheme());

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 4xx answers are definitive (frozen error envelope) — only retry transport/5xx.
      retry: (failureCount, error) => !(error instanceof ApiError && error.status < 500) && failureCount < 2,
      refetchOnWindowFocus: false,
    },
  },
});

const router = createAppRouter(queryClient);

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);
