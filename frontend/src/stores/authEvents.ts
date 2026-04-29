/**
 * Auth 事件中心
 * 用于解耦 authStore 和其他 store，避免直接依赖
 */

type AuthEventType = "login" | "logout";

interface AuthEvent {
  type: AuthEventType;
  timestamp: number;
}

type AuthEventListener = (event: AuthEvent) => void;

class AuthEventEmitter {
  private listeners: Set<AuthEventListener> = new Set();

  subscribe(listener: AuthEventListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  emit(type: AuthEventType) {
    const event: AuthEvent = { type, timestamp: Date.now() };
    this.listeners.forEach((listener) => listener(event));
  }
}

export const authEvents = new AuthEventEmitter();
