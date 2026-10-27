import React from 'react';
import { useFertiTrack } from '../../context/FertiTrackContext';

export const AnalisisView: React.FC = () => {
  const {
    sensor,
    conteoRiegos,
    insightActivo,
    descartarInsight,
    aplicarSugerenciaInsight,
    exportarBitacoraCSV,
  } = useFertiTrack();

  const estadoTexto =
    sensor.humedadPorcentaje >= 60
      ? 'ÓPTIMA'
      : sensor.humedadPorcentaje >= 35
      ? 'MEDIA'
      : 'SECO';

  return (
    <div className="px-4 pt-3 pb-28 space-y-4">
      {/* Header */}
      <div>
        <div className="flex items-center justify-between">
          <h1 className="text-[28px] font-extrabold tracking-tight text-slate-900">Análisis</h1>
          <span className="text-[13px] font-semibold text-slate-500">Sesión · 3 h 12 min</span>
        </div>
        <p className="text-[13px] text-slate-400 font-medium mt-0.5">
          Desliza hacia abajo para refrescar
        </p>
      </div>

      {/* Card 1: HUMEDAD PROMEDIO */}
      <div className="bg-white rounded-3xl p-4 border border-slate-100 shadow-sm flex items-center justify-between">
        <div className="flex items-center gap-3.5">
          <div className="w-12 h-12 rounded-2xl bg-emerald-50 flex items-center justify-center text-2xl">
            💧
          </div>
          <div>
            <p className="text-[11px] font-bold tracking-wider text-slate-400 uppercase">
              HUMEDAD PROMEDIO
            </p>
            <p className="text-[34px] font-extrabold text-slate-900 leading-none mt-1">
              52<span className="text-xl font-bold text-slate-400 ml-0.5">%</span>
            </p>
          </div>
        </div>
        <div className="text-right">
          <p className="text-[14px] font-extrabold text-red-600">−12 pts</p>
          <p className="text-xs text-slate-400">vs. ayer</p>
        </div>
      </div>

      {/* Card 2: RIEGOS DE LA SESIÓN */}
      <div className="bg-white rounded-3xl p-4 border border-slate-100 shadow-sm flex items-center justify-between">
        <div className="flex items-center gap-3.5">
          <div className="w-12 h-12 rounded-2xl bg-sky-50 flex items-center justify-center text-2xl">
            🚿
          </div>
          <div>
            <p className="text-[11px] font-bold tracking-wider text-slate-400 uppercase">
              RIEGOS DE LA SESIÓN
            </p>
            <p className="text-[34px] font-extrabold text-slate-900 leading-none mt-1">
              {conteoRiegos}
            </p>
          </div>
        </div>
        <div className="text-right">
          <p className="text-[14px] font-extrabold text-emerald-600">Ciclo ✓</p>
          <p className="text-xs text-slate-400">completo</p>
        </div>
      </div>

      {/* Card 3: ESTADO ACTUAL */}
      <div className="bg-white rounded-3xl p-4 border border-slate-100 shadow-sm flex items-center justify-between">
        <div className="flex items-center gap-3.5">
          <div className="w-12 h-12 rounded-2xl bg-amber-50 flex items-center justify-center text-2xl">
            🪴
          </div>
          <div>
            <p className="text-[11px] font-bold tracking-wider text-slate-400 uppercase">
              ESTADO ACTUAL
            </p>
            <p className="text-[28px] font-extrabold text-amber-800 leading-none mt-1.5">
              {estadoTexto}
            </p>
          </div>
        </div>
        <span className="w-3 h-3 rounded-full bg-amber-400 mr-1" />
      </div>

      {/* Card 4: INSIGHT DEL SISTEMA */}
      {insightActivo && (
        <div className="bg-[#15733B] text-white rounded-3xl p-5 shadow-md space-y-4">
          <div className="flex items-center gap-2 text-xs font-extrabold tracking-wider uppercase text-emerald-100">
            <span className="text-base">🧠</span>
            <span>INSIGHT DEL SISTEMA</span>
          </div>

          <p className="text-[16px] font-bold leading-snug text-white">
            El sector 3 concentra el 70% de la pérdida de humedad de hoy. Sugerencia: adelantar su
            riego a las 16:00 y aplicar fertilizante después del segundo ciclo.
          </p>

          <div className="grid grid-cols-2 gap-3 pt-1">
            <button
              type="button"
              onClick={descartarInsight}
              className="py-3 px-4 rounded-2xl bg-white/15 hover:bg-white/20 text-white font-bold text-[14px]"
            >
              Descartar
            </button>
            <button
              type="button"
              onClick={aplicarSugerenciaInsight}
              className="py-3 px-4 rounded-2xl bg-white text-emerald-900 font-extrabold text-[14px] shadow-sm"
            >
              Aplicar sugerencia
            </button>
          </div>
        </div>
      )}

      {/* Card 5: Exportar bitácora */}
      <button
        type="button"
        onClick={exportarBitacoraCSV}
        className="w-full bg-white rounded-3xl p-4 border border-slate-100 shadow-sm flex items-center gap-3.5 text-left hover:border-emerald-200"
      >
        <div className="w-11 h-11 rounded-2xl bg-indigo-50 flex items-center justify-center text-xl shrink-0">
          📥
        </div>
        <div>
          <h3 className="text-[15px] font-bold text-slate-900">
            Exportar bitácora de la sesión
          </h3>
          <p className="text-[13px] text-slate-500 font-medium">
            CSV con lecturas ADC y eventos
          </p>
        </div>
      </button>
    </div>
  );
};
