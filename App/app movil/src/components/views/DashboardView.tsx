import React from 'react';
import { useFertiTrack } from '../../context/FertiTrackContext';

export const DashboardView: React.FC = () => {
  const {
    sensor,
    conteoRiegos,
    mostrarAlertaFertilizacion,
    confirmarFertilizacion,
    ignorarFertilizacion,
    esclavoConectado,
    bitacoraEventos,
  } = useFertiTrack();

  const estadoActual =
    sensor.humedadPorcentaje >= 60
      ? 'HUMEDO'
      : sensor.humedadPorcentaje >= 35
      ? 'MEDIA'
      : 'SECO';

  const colorBarra =
    estadoActual === 'HUMEDO'
      ? 'bg-emerald-500'
      : estadoActual === 'MEDIA'
      ? 'bg-amber-400'
      : 'bg-red-500';

  return (
    <div className="px-4 pt-3 pb-28 space-y-4">
      {/* Header principal */}
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-[28px] font-extrabold tracking-tight text-slate-900 leading-none">
            FertiTrack
          </h1>
          <p className="text-[14px] text-slate-500 font-medium mt-1">
            Invernadero automatizado
          </p>
        </div>
        <span className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-bold bg-emerald-100/90 text-emerald-700">
          <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
          EN VIVO
        </span>
      </div>

      {/* Pills Sector y Punto */}
      <div className="flex items-center gap-2.5 flex-wrap">
        <span className="inline-flex items-center gap-1.5 px-3.5 py-1.5 rounded-full text-[13px] font-semibold bg-white border border-emerald-200 text-emerald-800 shadow-sm">
          <span>📍</span> Sector {sensor.sectorActual} activo
        </span>
        <span className="inline-flex items-center gap-1.5 px-3.5 py-1.5 rounded-full text-[13px] font-semibold bg-sky-100/80 border border-sky-200 text-sky-800">
          <span>💧</span> Punto {sensor.puntoSensado}/3
        </span>
      </div>

      {/* Carrusel horizontal de KPIs */}
      <div className="carousel-x flex gap-3 pb-1 -mx-1 px-1">
        {/* Card 1: Humedad */}
        <div className="min-w-[148px] flex-1 bg-white rounded-2xl p-4 border border-slate-100 shadow-sm snap-start">
          <div className="w-10 h-10 rounded-xl bg-emerald-50 flex items-center justify-center text-xl mb-3">
            💧
          </div>
          <p className="text-[11px] font-bold tracking-wider text-slate-400 uppercase">
            HUMEDAD
          </p>
          <p className="text-[32px] font-extrabold text-slate-900 leading-tight mt-0.5">
            {sensor.humedadPorcentaje}
            <span className="text-lg font-bold text-slate-400 ml-0.5">%</span>
          </p>
        </div>

        {/* Card 2: Riegos Hoy */}
        <div className="min-w-[148px] flex-1 bg-white rounded-2xl p-4 border border-slate-100 shadow-sm snap-start">
          <div className="w-10 h-10 rounded-xl bg-sky-50 flex items-center justify-center text-xl mb-3">
            🚿
          </div>
          <p className="text-[11px] font-bold tracking-wider text-slate-400 uppercase">
            RIEGOS HOY
          </p>
          <p className="text-[32px] font-extrabold text-slate-900 leading-tight mt-0.5">
            {conteoRiegos}
          </p>
        </div>

        {/* Card 3: Suelo */}
        <div className="min-w-[148px] flex-1 bg-white rounded-2xl p-4 border border-slate-100 shadow-sm snap-start">
          <div className="w-10 h-10 rounded-xl bg-amber-50 flex items-center justify-center text-xl mb-3">
            🪴
          </div>
          <p className="text-[11px] font-bold tracking-wider text-slate-400 uppercase">
            SUELO
          </p>
          <p className="text-[26px] font-extrabold text-amber-800 leading-tight mt-1">
            {estadoActual}
          </p>
        </div>
      </div>

      {/* Tarjeta Humedad del suelo */}
      <div className="bg-white rounded-3xl p-5 border border-slate-100 shadow-sm space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="text-[17px] font-bold text-slate-900">Humedad del suelo</h2>
          <span className="font-mono text-xs text-slate-400">HumedadPorcentaje</span>
        </div>

        <div className="flex items-center gap-4">
          <div className="text-[48px] font-extrabold text-slate-900 leading-none tracking-tight">
            {sensor.humedadPorcentaje}
            <span className="text-2xl font-bold text-slate-400 ml-0.5">%</span>
          </div>
          <div className="text-[14px] text-slate-500 font-medium leading-snug">
            {estadoActual === 'HUMEDO' && (
              <>
                Nivel óptimo,
                <br />
                sin riego requerido
              </>
            )}
            {estadoActual === 'MEDIA' && (
              <>
                Nivel intermedio,
                <br />
                riego programado
              </>
            )}
            {estadoActual === 'SECO' && (
              <>
                Nivel crítico seco,
                <br />
                riego urgente
              </>
            )}
          </div>
        </div>

        {/* Barra de progreso */}
        <div className="w-full h-2.5 bg-slate-100 rounded-full overflow-hidden">
          <div
            className={`h-full rounded-full transition-all duration-500 ${colorBarra}`}
            style={{ width: `${sensor.humedadPorcentaje}%` }}
          />
        </div>

        {/* Selector de 3 estados */}
        <div className="grid grid-cols-3 gap-2 pt-1">
          <div
            className={`flex items-center justify-center gap-1.5 py-2 px-2 rounded-xl text-[13px] font-semibold border ${
              estadoActual === 'HUMEDO'
                ? 'bg-emerald-50 border-emerald-300 text-emerald-800'
                : 'bg-slate-50/80 border-transparent text-slate-500'
            }`}
          >
            <span className="w-2 h-2 rounded-full bg-emerald-500" />
            Húmedo
          </div>
          <div
            className={`flex items-center justify-center gap-1.5 py-2 px-2 rounded-xl text-[13px] font-bold border ${
              estadoActual === 'MEDIA'
                ? 'bg-amber-50/90 border-amber-300 text-amber-800'
                : 'bg-slate-50/80 border-transparent text-slate-500'
            }`}
          >
            <span className="w-2 h-2 rounded-full bg-amber-500" />
            Intermedio
          </div>
          <div
            className={`flex items-center justify-center gap-1.5 py-2 px-2 rounded-xl text-[13px] font-semibold border ${
              estadoActual === 'SECO'
                ? 'bg-red-50 border-red-300 text-red-700'
                : 'bg-slate-50/80 border-transparent text-slate-500'
            }`}
          >
            <span className="w-2 h-2 rounded-full bg-red-500" />
            Seco
          </div>
        </div>
      </div>

      {/* Tarjeta Esclavo — sensor en tiempo real */}
      <div className="bg-white rounded-2xl p-4 border border-slate-100 shadow-sm flex items-center justify-between">
        <div className="flex items-center gap-3.5">
          <div className="w-11 h-11 rounded-2xl bg-sky-50 flex items-center justify-center text-xl">
            📡
          </div>
          <div>
            <h3 className="text-[15px] font-bold text-slate-900">
              Esclavo — sensor en tiempo real
            </h3>
            <p className="text-[13px] text-slate-500 font-medium mt-0.5">
              ADC <strong className="text-slate-800 font-mono">{sensor.valorADC}</strong> · lectura #
              {sensor.numeroLectura} · cada 3 s
            </p>
          </div>
        </div>
        <span
          className={`w-2.5 h-2.5 rounded-full ${
            esclavoConectado ? 'bg-emerald-500' : 'bg-emerald-400 animate-pulse'
          }`}
        />
      </div>

      {/* Tarjeta Fertilización pendiente */}
      {mostrarAlertaFertilizacion && (
        <div className="bg-[#FEF9E7] rounded-3xl p-4 border border-amber-300/80 shadow-sm space-y-3.5">
          <div className="flex items-start gap-3">
            <div className="w-11 h-11 rounded-full bg-amber-500 text-white flex items-center justify-center text-xl shrink-0">
              🌾
            </div>
            <div>
              <h3 className="text-[16px] font-extrabold text-amber-900">
                Fertilización pendiente
              </h3>
              <p className="text-[13.5px] text-amber-800/90 font-medium mt-0.5">
                El ciclo terminó. ¿Aplicar fertilizante al Sector {sensor.sectorActual}?
              </p>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <button
              type="button"
              onClick={ignorarFertilizacion}
              className="py-3 px-4 rounded-2xl border border-amber-300 bg-[#FFFDF5] text-amber-900 font-bold text-[15px]"
            >
              Ignorar
            </button>
            <button
              type="button"
              onClick={confirmarFertilizacion}
              className="py-3 px-4 rounded-2xl bg-amber-500 text-white font-bold text-[15px] shadow-sm"
            >
              Sí, empezar
            </button>
          </div>
        </div>
      )}

      {/* Tarjeta ESTADO DE LA MÁQUINA */}
      <div className="bg-white rounded-2xl p-4 border-l-4 border-l-emerald-500 border border-slate-100 shadow-sm space-y-2">
        <p className="text-[11px] font-bold tracking-wider text-slate-400 uppercase">
          ESTADO DE LA MÁQUINA
        </p>
        <p className="text-[15px] font-bold text-slate-800">{sensor.estadoMaquina}</p>
        <div className="pt-1 border-t border-slate-100 space-y-1">
          {bitacoraEventos.slice(0, 2).map((ev, i) => (
            <p key={i} className="text-xs font-mono text-slate-500 truncate">
              {ev}
            </p>
          ))}
        </div>
      </div>
    </div>
  );
};
