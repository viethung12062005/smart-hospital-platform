import '@testing-library/jest-dom/vitest';
import { cleanup, configure } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { setConfigForTest } from './src/config';
import './src/i18n';
import { server } from './src/mocks/server';

// findBy*/waitFor chờ tối đa 5 giây (mặc định 1 giây dễ đỏ oan trên runner CI chậm).
configure({ asyncUtilTimeout: 5000 });

// Node fetch cần URL tuyệt đối: gọi API qua origin của jsdom, MSW chặn theo đường dẫn.
setConfigForTest({ authMode: 'mock', apiBase: window.location.origin });

// Mantine cần các API trình duyệt mà jsdom không có.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
});
window.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
};
window.HTMLElement.prototype.scrollIntoView = () => {};

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  cleanup();
  server.resetHandlers();
});
afterAll(() => server.close());
