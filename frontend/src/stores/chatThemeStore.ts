import { create } from "zustand";

export type ChatTheme = "aurora" | "crystal";

interface ChatThemeState {
  theme: ChatTheme;
  setTheme: (theme: ChatTheme) => void;
  toggleTheme: () => void;
}

const STORAGE_KEY = "ragent_chat_theme";

function safeGet(key: string) {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function safeSet(key: string, value: string) {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    return;
  }
}

export const useChatThemeStore = create<ChatThemeState>((set, get) => ({
  theme: "crystal" as ChatTheme,
  setTheme: (theme) => {
    safeSet(STORAGE_KEY, theme);
    set({ theme });
  },
  toggleTheme: () => {
    const next = get().theme === "aurora" ? "crystal" : "aurora";
    get().setTheme(next);
  },
  initialize: () => {
    const stored = safeGet(STORAGE_KEY);
    const theme: ChatTheme = stored === "aurora" ? "aurora" : "crystal";
    set({ theme });
  }
}));

// Auto-initialize on import - visual theming handled by ThemeContext
export const initializeChatTheme = () => useChatThemeStore.getState().initialize();

if (typeof window !== "undefined") {
  initializeChatTheme();
}
