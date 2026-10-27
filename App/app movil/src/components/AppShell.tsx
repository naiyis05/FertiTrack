import React from 'react';
import { useFertiTrack } from '../context/FertiTrackContext';
import { PestanaActiva } from '../types/sensor';
import { DashboardView } from './views/DashboardView';
import { InvernaderoView } from './views/InvernaderoView';
import { GraficasView } from './views/GraficasView';
import { ControlView } from './views/ControlView';
import { AnalisisView } from './views/AnalisisView';
import { DanielaDrawer } from './daniela/DanielaDrawer';

const NAV_ITEMS: { id: PestanaActiva; label: string; icon: string }[] = [
  { id: 'Dashboard', label: 'Dashboard', icon: '🏠' },
  { id: 'Invernadero', label: 'Invernadero', icon: '🌿' },
  { id: 'Graficas', label: 'Gráficas', icon: '📈' },
  { id: 'Control', label: 'Control', icon: '⚙️' },
  { id: 'Analisis', label: 'Análisis', icon: '📊' },
];

export const AppShell: React.FC = () => {
  const {
    pestanaActiva,
    setPestanaActiva,
    setDanielaAbierta,
    notificacionesDaniela,
  } = useFertiTrack();

  return (
    <div className="min-h-[100dvh] bg-slate-200/70 flex justify-center">
      {/* Contenedor responsive (móvil nativo en teléfonos, frame centrado limpio en escritorio) */}
      <div className="w-full max-w-[430px] app-shell bg-ferti-bg relative flex flex-col shadow-xl overflow-hidden">
        {/* Área de contenido desplazable */}
        <main className="flex-1 scroll-container">
          {pestanaActiva === 'Dashboard' && <DashboardView />}
          {pestanaActiva === 'Invernadero' && <InvernaderoView />}
          {pestanaActiva === 'Graficas' && <GraficasView />}
          {pestanaActiva === 'Control' && <ControlView />}
          {pestanaActiva === 'Analisis' && <AnalisisView />}
        </main>

        {/* Botón flotante de Daniela (Asistente de FertiTrack) */}
        <button
          type="button"
          onClick={() => setDanielaAbierta(true)}
          className="fixed bottom-20 right-5 sm:right-[calc(50%-195px)] z-30 w-14 h-14 rounded-full bg-[#1DB954] hover:bg-emerald-600 text-white shadow-lg flex items-center justify-center text-2xl border-2 border-white"
          aria-label="Abrir asistente Daniela"
        >
          <span>👩‍🌾</span>
          {notificacionesDaniela > 0 && (
            <span className="absolute -top-1 -right-1 w-5 h-5 rounded-full bg-red-500 text-white text-[11px] font-extrabold flex items-center justify-center border-2 border-white">
              {notificacionesDaniela}
            </span>
          )}
        </button>

        {/* Barra de Navegación Inferior (5 Pestañas idénticas a las capturas) */}
        <nav className="bg-white border-t border-slate-200/80 px-2 pt-2 pb-3 grid grid-cols-5 shrink-0 z-20">
          {NAV_ITEMS.map((item) => {
            const activo = pestanaActiva === item.id;
            return (
              <button
                key={item.id}
                type="button"
                onClick={() => setPestanaActiva(item.id)}
                className="flex flex-col items-center justify-center gap-1 py-1 relative"
              >
                <span className={`text-xl ${activo ? 'scale-105' : 'opacity-65'}`}>
                  {item.icon}
                </span>
                <span
                  className={`text-[11px] ${
                    activo ? 'font-extrabold text-emerald-600' : 'font-medium text-slate-400'
                  }`}
                >
                  {item.label}
                </span>
                {activo && (
                  <span className="w-5 h-1 rounded-full bg-emerald-500 mt-0.5" />
                )}
              </button>
            );
          })}
        </nav>

        {/* Modal/Drawer de Daniela */}
        <DanielaDrawer />
      </div>
    </div>
  );
};
