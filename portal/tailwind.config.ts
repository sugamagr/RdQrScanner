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
          '100%': { transform: 'translateX(300%)' },
        },
        // Loader: dual concentric arcs rotating in opposite directions
        // at different speeds — the offset rotation gives the eye
        // something to track even at low motion (designer-quality
        // detail vs the default browser-spinner monotony).
        'loader-spin-cw': {
          '0%': { transform: 'rotate(0deg)' },
          '100%': { transform: 'rotate(360deg)' },
        },
        'loader-spin-ccw': {
          '0%': { transform: 'rotate(0deg)' },
          '100%': { transform: 'rotate(-360deg)' },
        },
        // Subtle breathing pulse on the centre dot. 1.4s matches a
        // calm-resting heart rate (≈42bpm) — the same cadence Apple
        // uses for the iOS activity indicator's opacity loop.
        'loader-pulse': {
          '0%, 100%': { opacity: '0.4', transform: 'scale(0.85)' },
          '50%': { opacity: '1', transform: 'scale(1)' },
        },
        // Skeleton shimmer: a soft highlight band traverses the
        // placeholder L→R. 1.6s feels alive without distracting.
        'loader-shimmer': {
          '0%': { transform: 'translateX(-100%)' },
          '100%': { transform: 'translateX(100%)' },
        },
        // Floating bubbles for the themed full-page loader. Each
        // bubble uses a different keyframe so the path varies (eye
        // sees individual motion instead of a synchronized swarm).
        // translateY-only is GPU-cheap; opacity fade prevents the
        // bubble from popping in/out at the bounds.
        'bubble-a': {
          '0%, 100%': { transform: 'translate3d(0,0,0) scale(1)', opacity: '0.55' },
          '50%': { transform: 'translate3d(8px,-32px,0) scale(1.08)', opacity: '0.75' },
        },
        'bubble-b': {
          '0%, 100%': { transform: 'translate3d(0,0,0) scale(1)', opacity: '0.42' },
          '50%': { transform: 'translate3d(-12px,-44px,0) scale(0.92)', opacity: '0.6' },
        },
        'bubble-c': {
          '0%, 100%': { transform: 'translate3d(0,0,0) scale(1)', opacity: '0.5' },
          '50%': { transform: 'translate3d(14px,-24px,0) scale(1.04)', opacity: '0.7' },
        },
        // Background hue drift on the themed loader — extremely slow
        // gradient position shift so the page feels alive without
        // becoming distracting.
        'gradient-drift': {
          '0%, 100%': { backgroundPosition: '0% 50%' },
          '50%': { backgroundPosition: '100% 50%' },
        },
      },
      animation: {
        'csv-progress': 'csv-progress 1.2s ease-in-out infinite',
        'loader-spin-cw': 'loader-spin-cw 1.2s linear infinite',
        'loader-spin-ccw': 'loader-spin-ccw 1.8s linear infinite',
        'loader-pulse': 'loader-pulse 1.4s ease-in-out infinite',
        'loader-shimmer': 'loader-shimmer 1.6s ease-in-out infinite',
        'bubble-a': 'bubble-a 7s ease-in-out infinite',
        'bubble-b': 'bubble-b 9s ease-in-out infinite',
        'bubble-c': 'bubble-c 11s ease-in-out infinite',
        'gradient-drift': 'gradient-drift 14s ease-in-out infinite',
      },
    },
  },
  plugins: [],
};

export default config;
