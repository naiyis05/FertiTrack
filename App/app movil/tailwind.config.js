/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['"Plus Jakarta Sans"', 'Inter', 'system-ui', '-apple-system', 'sans-serif'],
        mono: ['"JetBrains Mono"', 'ui-monospace', 'SFMono-Regular', 'monospace'],
      },
      colors: {
        ferti: {
          bg: '#F3F7F4',
          card: '#FFFFFF',
          border: '#E6EFE9',
          green: '#16A34A',
          greenLight: '#DCFCE7',
          greenDark: '#146C34',
          amber: '#F59E0B',
          amberLight: '#FEF9E7',
          red: '#EF4444',
          redLight: '#FEE2E2',
          blue: '#2563EB',
          blueLight: '#E0F2FE',
        },
      },
    },
  },
  plugins: [],
};
