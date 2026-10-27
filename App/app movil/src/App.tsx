import React from 'react';
import { FertiTrackProvider } from './context/FertiTrackContext';
import { AppShell } from './components/AppShell';

export function App() {
  return (
    <FertiTrackProvider>
      <AppShell />
    </FertiTrackProvider>
  );
}

export default App;
