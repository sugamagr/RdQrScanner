import type { Config } from 'tailwindcss';

// Palette mirrors the phone's ui/theme/Color.kt so portal + phones share
// the same visual language end-to-end. Values verified against
// app/src/main/java/com/qrscanner/app/ui/theme/Color.kt lines 6-33.
const config: Config = {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        primary: {
          DEFAULT: '#FF9F43',
          light: '#FFBE76',
          dark: '#E67E22',
        },
        accent: {
          coral: '#FF6B6B',
          mint: '#4ECDC4',
          'mint-ink': '#0E8278',
        },
        warn: '#F59E0B',
        danger: '#EF4444',
        ink: {
          primary: '#111827',
          secondary: '#6B7280',
          muted: '#9CA3AF',
        },
        surface: {
          DEFAULT: '#FFFFFF',
          alt: '#F9FAFB',
          border: '#E5E7EB',
        },
      },
      fontFamily: {
        sans: [
          'Inter',
          '-apple-system',
          'BlinkMacSystemFont',
          'Segoe UI',
          'Roboto',
          'sans-serif',
        ],
      },
      boxShadow: {
        card: '0 1px 2px 0 rgba(17, 24, 39, 0.04), 0 1px 3px 0 rgba(17, 24, 39, 0.06)',
        elevated: '0 4px 12px -2px rgba(17, 24, 39, 0.08), 0 2px 4px -1px rgba(17, 24, 39, 0.04)',
      },
      borderRadius: {
        pill: '9999px',
      },
      keyframes: {
        'csv-progress': {
          '0%': { transform: 'translateX(-100%)' },
          '50%': { transform: 'translateX(150%)' },
          '100%': { transform: 'translateX(350%)' },
        },
      },
      animation: {
        'csv-progress': 'csv-progress 1.2s ease-in-out infinite',
      },
    },
  },
  plugins: [],
};

export default config;
