import React, { useState } from 'react';
import { useFertiTrack } from '../../context/FertiTrackContext';

export const ControlView: React.FC = () => {
  const {
    riegoAutomatico,
    setRiegoAutomatico,
    umbralSecoPct,
    fertilizarManualmente,
    conectadoMaestro,
    ipMaestro,
    setIpMaestro,
    toggleConexionMaestro,
    mensajeErrorMaestro,
    esclavoConectado,
    ipEsclavo,
    setIpEsclavo,
    toggleConexionEsclavo,
    mensajeErrorEsclavo,
    detenerCicloEmergencia,
  } = useFertiTrack();

  const [mostrarMenuSecundario, setMostrarMenuSecundario] = useState(false);

  return (
    <div className="px-4 pt-3 pb-28 space-y-4">
      <h1 className="text-[28px] font-extrabold tracking-tight text-slate-900">Control</h1>

      {/* Tarjeta Riego automático */}
      <div className="bg-white rounded-3xl p-4 border border-slate-100 shadow-sm flex items-center justify-between">
        <div className="flex items-center gap-3.5">
          <div className="w-12 h-12 rounded-2xl bg-sky-50 flex items-center justify-center text-2xl">
            🚿
          </div>
          <div>
            <h2 className="text-[16px] font-bold text-slate-900">Riego automático</h2>
            <p className="text-[13.5px] font-semibold text-emerald-600">
              {riegoAutomatico ? `Activo · umbral ${umbralSecoPct}%` : 'Pausado manualmente'}
            </p>
          </div>
        </div>

        {/* Toggle Switch estilo nativo */}
        <button
          type="button"
          role="switch"
          aria-checked={riegoAutomatico}
          onClick={() => setRiegoAutomatico(!riegoAutomatico)}
          className={`w-14 h-8 rounded-full p-1 transition-colors duration-200 flex items-center ${
            riegoAutomatico ? 'bg-emerald-500 justify-end' : 'bg-slate-300 justify-start'
          }`}
        >
          <span className="w-6 h-6 rounded-full bg-white shadow-md block" />
        </button>
      </div>

      {/* Tarjeta Fertilización manual */}
      <div className="bg-white rounded-3xl p-4 border border-slate-100 shadow-sm space-y-3.5">
        <div className="flex items-center gap-3.5">
          <div className="w-12 h-12 rounded-2xl bg-amber-50 flex items-center justify-center text-2xl">
            🌾
          </div>
          <div>
            <h2 className="text-[16px] font-bold text-slate-900">Fertilización manual</h2>
            <p className="text-[13.5px] text-slate-500 font-medium">
              Dosis única en el sector activo
            </p>
          </div>
        </div>

        <button
          type="button"
          onClick={fertilizarManualmente}
          className="w-full py-3.5 rounded-2xl bg-amber-500 hover:bg-amber-600 text-white font-extrabold text-[16px] shadow-sm"
        >
          Fertilizar ahora
        </button>
      </div>

      {/* Sección CONEXIONES */}
      <div className="pt-1 space-y-3">
        <p className="text-[12px] font-bold tracking-wider text-slate-400 uppercase px-1">
          CONEXIONES
        </p>

        {/* Tarjeta Maestro (robot) */}
        <div className="bg-white rounded-3xl p-4 border border-slate-100 shadow-sm space-y-3">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-11 h-11 rounded-2xl bg-emerald-50 flex items-center justify-center text-xl">
                🤖
              </div>
              <div>
                <h3 className="text-[16px] font-bold text-slate-900">Maestro (robot)</h3>
                <p className="font-mono text-xs text-slate-400">TCP · puerto 8888</p>
              </div>
            </div>

            <span
              className={`inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-bold ${
                conectadoMaestro
                  ? 'bg-emerald-100/90 text-emerald-700'
                  : 'bg-slate-100 text-slate-500'
              }`}
            >
              <span
                className={`w-2 h-2 rounded-full ${
                  conectadoMaestro ? 'bg-emerald-500' : 'bg-slate-400'
                }`}
              />
              {conectadoMaestro ? 'CONECTADO' : 'DESCONECTADO'}
            </span>
          </div>

          <div className="flex gap-2.5">
            <input
              type="text"
              inputMode="decimal"
              value={ipMaestro}
              onChange={(e) => setIpMaestro(e.target.value)}
              placeholder="192.168.1.50"
              className="flex-1 bg-slate-50 border border-slate-200 rounded-2xl px-4 py-3 font-mono text-[16px] text-slate-900 focus:outline-none focus:border-emerald-500"
            />
            <button
              type="button"
              onClick={toggleConexionMaestro}
              className={`px-5 py-3 rounded-2xl font-bold text-[15px] ${
                conectadoMaestro
                  ? 'bg-slate-100 text-slate-800 hover:bg-slate-200'
                  : 'bg-emerald-600 text-white'
              }`}
            >
              {conectadoMaestro ? 'Desconectar' : 'Conectar'}
            </button>
          </div>

          {mensajeErrorMaestro ? (
            <p className="text-xs font-semibold text-red-600">{mensajeErrorMaestro}</p>
          ) : (
            <p className="text-xs text-slate-400">
              Teclado numérico con punto · autocompletado de subred
            </p>
          )}
        </div>

        {/* Tarjeta Esclavo (sensor) */}
        <div className="bg-white rounded-3xl p-4 border border-slate-100 shadow-sm space-y-3">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-11 h-11 rounded-2xl bg-red-50 flex items-center justify-center text-xl">
                📡
              </div>
              <div>
                <h3 className="text-[16px] font-bold text-slate-900">Esclavo (sensor)</h3>
                <p className="font-mono text-xs text-slate-400">
                  HTTP · puerto 80 · polling 3 s
                </p>
              </div>
            </div>

            <span
              className={`inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-bold ${
                esclavoConectado
                  ? 'bg-emerald-100/90 text-emerald-700'
                  : 'bg-red-100/90 text-red-600'
              }`}
            >
              <span
                className={`w-2 h-2 rounded-full ${
                  esclavoConectado ? 'bg-emerald-500' : 'bg-red-500'
                }`}
              />
              {esclavoConectado ? 'CONECTADO' : 'SIN SEÑAL'}
            </span>
          </div>

          <div className="flex gap-2.5">
            <input
              type="text"
              inputMode="decimal"
              value={ipEsclavo}
              onChange={(e) => setIpEsclavo(e.target.value)}
              placeholder="192.168.1.7"
              className={`flex-1 bg-white border rounded-2xl px-4 py-3 font-mono text-[16px] text-slate-900 focus:outline-none ${
                !esclavoConectado && mensajeErrorEsclavo
                  ? 'border-red-300 focus:border-red-500'
                  : 'border-slate-200 focus:border-emerald-500'
              }`}
            />
            <button
              type="button"
              onClick={toggleConexionEsclavo}
              className={`px-6 py-3 rounded-2xl font-bold text-[15px] ${
                esclavoConectado
                  ? 'bg-slate-100 text-slate-800'
                  : 'bg-emerald-600 text-white shadow-sm'
              }`}
            >
              {esclavoConectado ? 'Detener' : 'Conectar'}
            </button>
          </div>

          {!esclavoConectado && mensajeErrorEsclavo && (
            <div className="flex items-start gap-2 text-red-600 text-[13px] font-bold pt-0.5">
              <span>⚠️</span>
              <span>{mensajeErrorEsclavo}</span>
            </div>
          )}
        </div>
      </div>

      {/* Nota sobre acciones destructivas */}
      <div className="bg-white/80 rounded-2xl p-4 border border-slate-100 space-y-2">
        <button
          type="button"
          onClick={() => setMostrarMenuSecundario(!mostrarMenuSecundario)}
          className="w-full text-left flex items-start gap-2.5 text-[13px] text-slate-500"
        >
          <span>💡</span>
          <span>
            Las acciones destructivas (detener ciclo, reiniciar placas) viven en un menú secundario
            para evitar toques accidentales.
          </span>
        </button>

        {mostrarMenuSecundario && (
          <div className="pt-2 border-t border-slate-100 flex gap-2">
            <button
              type="button"
              onClick={detenerCicloEmergencia}
              className="flex-1 py-2.5 px-3 rounded-xl bg-red-50 text-red-700 font-bold text-xs border border-red-200"
            >
              🛑 Detener ciclo ahora
            </button>
          </div>
        )}
      </div>
    </div>
  );
};
