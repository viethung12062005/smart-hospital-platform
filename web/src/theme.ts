import { createTheme, type MantineColorsTuple } from '@mantine/core';

// Màu teal của hệ thiết kế (src/styles/tokens.css): shade 6 = --shp-teal, 8 = --shp-teal-d, 0 = --shp-teal-soft.
const teal: MantineColorsTuple = [
  '#e6f2ef',
  '#cde5df',
  '#a3cfc4',
  '#76b8a8',
  '#4f9f8e',
  '#2f877a',
  '#0f6d62',
  '#0c5f55',
  '#0a5249',
  '#063a33',
];

export const theme = createTheme({
  primaryColor: 'shp',
  primaryShade: 6,
  colors: { shp: teal },
  fontFamily: 'Inter, Roboto, "Helvetica Neue", Arial, sans-serif',
  fontFamilyMonospace: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace',
  headings: { fontWeight: '600' },
  defaultRadius: 'md',
  radius: { md: '8px', lg: '10px' },
  fontSizes: { sm: '13px', md: '14px' },
  black: '#263238',
});
