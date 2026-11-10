import '@testing-library/jest-dom/vitest';
import { cleanup, configure } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { setConfigForTest } from './src/config';
import './src/i18n';
import { server } from './src/mocks/server';

// findBy*/waitFor waits up to 5 seconds (the 1-second default causes false failures on slow CI runners).
configure({ asyncUtilTimeout: 5000 });

// Node fetch needs absolute URLs: call the API through the jsdom origin, MSW intercepts by path.
setConfigForTest({ authMode: 'mock', apiBase: window.location.origin });

// Mantine needs browser APIs that jsdom does not provide.
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
