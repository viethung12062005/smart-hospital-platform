/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_MOCK?: string;
}

declare module '*.yaml?raw' {
  const content: string;
  export default content;
}
