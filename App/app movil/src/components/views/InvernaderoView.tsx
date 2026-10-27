import React, { useState, useEffect } from 'react';
import { useFertiTrack } from '../../context/FertiTrackContext';

const ICONOS_FRANJAS = ['🌌', '🌗', '🌅', '🌤️', '☀️', '🌇', '🌆', '🌙'];

export const InvernaderoView: React.FC = () => {
  const {
    sensor,
    sectores,
    isNight,
    franjaHorariaIdx,
    setFranjaHorariaIdx,
    seleccionarSectorActivo,
    regarSectorAhora,
  } = useFertiTrack();

  const [horaTexto, setHoraTexto] = useState('09:41');
  const [fechaTexto, setFechaTexto] = useState('Lunes 27 de julio');

  useEffect(() => {
    const actualizar = () => {
      const ahora = new Date();
      setHoraTexto(
        ahora.toLocaleTimeString('es-CO', {
          hour: '2-digit',
          minute: '2-digit',
          hour12: false,
        })
      );
      const opciones: Intl.DateTimeFormatOptions = {
        weekday: 'long',
        day: 'numeric',
        month: 'long',
      };
      const f = ahora.toLocaleDateString('es-ES', opciones);
      setFechaTexto(f.charAt(0).toUpperCase() + f.slice(1));
    };
    actualizar();
    const id = window.setInterval(actualizar, 30000);
    return () => window.clearInterval(id);
  }, []);

  const posicionRobotPct =
    sensor.sectorActual === 1 ? '16%' : sensor.sectorActual === 2 ? '32%' : '84%';

  const sectorSeco = sectores.find((s) => s.humedad < 35) || sectores[2];

  return (
    <div className="pb-28">
      {/* Cabecera de Cielo Dinámico (Día / Noche igual que IsNight en MainViewModel.cs) */}
      <div
        className={`relative px-5 pt-4 pb-16 transition-colors duration-500 overflow-hidden ${
          isNight
            ? 'bg-gradient-to-b from-slate-900 via-indigo-950 to-slate-800 text-white'
            : 'bg-gradient-to-b from-[#2E9BF4] via-[#67C3F8] to-[#DDF2FD] text-white'
        }`}
      >
        {/* Sol o Luna decorativa */}
        <div
          className={`absolute top-5 right-8 w-20 h-20 rounded-full blur-[1px] transition-all duration-500 ${
            isNight
              ? 'bg-amber-100/80 shadow-[0_0_40px_rgba(254,243,199,0.5)]'
              : 'bg-yellow-300 shadow-[0_0_45px_rgba(253,224,71,0.85)]'
          }`}
        />

        <div className="relative z-10 flex items-start justify-between">
          <div>
            <div className="text-[44px] font-extrabold leading-none tracking-tight drop-shadow-sm">
              {horaTexto}
            </div>
            <p className="text-[15px] font-semibold text-white/90 mt-1">{fechaTexto}</p>
          </div>

          <button
            type="button"
            onClick={() => setFranjaHorariaIdx(isNight ? 3 : 7)}
            className="inline-flex items-center gap-2 px-4 py-2 rounded-full bg-white text-slate-800 font-bold text-sm shadow-md"
          >
            <span>{isNight ? '🌙' : '🌤️'}</span>
            <span>{isNight ? 'Noche' : 'Mañana'}</span>
          </button>
        </div>

        {/* Barra de íconos de ciclo horario */}
        <div className="relative z-10 mt-5 bg-white/25 backdrop-blur-md rounded-2xl p-1.5 grid grid-cols-8 gap-1 border border-white/30">
          {ICONOS_FRANJAS.map((icono, idx) => (
            <button
              key={idx}
              type="button"
              onClick={() => setFranjaHorariaIdx(idx)}
              className={`py-1.5 rounded-xl text-base flex items-center justify-center transition-all ${
                franjaHorariaIdx === idx
                  ? 'bg-white text-slate-900 shadow-sm scale-105'
                  : 'text-white/85 hover:bg-white/10'
              }`}
            >
              {icono}
            </button>
          ))}
        </div>
      </div>

      {/* Contenedor principal superpuesto sobre el cielo */}
      <div className="px-4 -mt-9 relative z-20 space-y-4">
        {/* Tarjeta de los 3 Sectores + Línea de ruta del Robot */}
        <div className="bg-white rounded-3xl p-4 border border-slate-100 shadow-sm space-y-5">
          <div className="grid grid-cols-3 gap-2.5">
            {sectores.map((sec) => {
              const esActivo = sensor.sectorActual === sec.id;
              const colorTexto =
                sec.humedad >= 60
                  ? 'text-slate-900'
                  : sec.humedad >= 35
                  ? 'text-slate-900'
                  : 'text-red-600';
              const colorBarra =
                sec.humedad >= 60
                  ? 'bg-emerald-500'
                  : sec.humedad >= 35
                  ? 'bg-amber-400'
                  : 'bg-red-500';

              return (
                <button
                  key={sec.id}
                  type="button"
                  onClick={() => seleccionarSectorActivo(sec.id)}
                  className={`rounded-2xl p-3 text-center transition-all border ${
                    esActivo
                      ? 'bg-emerald-50/70 border-emerald-400 shadow-sm'
                      : 'bg-slate-50/60 border-slate-100'
                  }`}
                >
                  <div className="flex items-center justify-center gap-1 text-[11px] font-extrabold tracking-wider uppercase text-slate-500">
                    {esActivo && <span className="w-1.5 h-1.5 rounded-full bg-emerald-500" />}
                    <span className={esActivo ? 'text-emerald-800' : ''}>{sec.nombre}</span>
                  </div>

                  <div className="my-2.5 text-2xl flex items-center justify-center gap-1">
                    {sec.humedad < 35 ? (
                      <>
                        <span>🌵</span>
                        <span>🌵</span>
                      </>
                    ) : (
                      <>
                        <span>🌿</span>
                        <span>🌿</span>
                      </>
                    )}
                  </div>

                  <div className={`text-[24px] font-extrabold leading-none ${colorTexto}`}>
                    {sec.humedad}%
                  </div>

                  <div className="w-full h-1.5 bg-slate-200/80 rounded-full overflow-hidden mt-2.5">
                    <div
                      className={`h-full rounded-full ${colorBarra}`}
                      style={{ width: `${sec.humedad}%` }}
                    />
                  </div>
                </button>
              );
            })}
          </div>

          {/* Track de posición del Robot */}
          <div className="pt-6 pb-1 px-3">
            <div className="relative h-2 bg-slate-200 rounded-full flex items-center justify-between">
              {/* Avatar flotante del Robot */}
              <div
                className="absolute -top-9 -translate-x-1/2 flex flex-col items-center transition-all duration-500"
                style={{ left: posicionRobotPct }}
              >
                <div className="w-10 h-10 rounded-2xl bg-slate-900 text-white flex items-center justify-center text-xl shadow-md">
                  🤖
                </div>
                <span className="text-[11px] font-extrabold text-emerald-700 mt-0.5">
                  S{sensor.sectorActual}
                </span>
              </div>

              {/* Nodo Inicio */}
              <div className="w-5 h-5 rounded-full bg-white border-4 border-slate-300 -ml-1" />
              {/* Nodo S2 */}
              <div
                className={`w-6 h-6 rounded-full bg-white border-4 ${
                  sensor.sectorActual === 2 ? 'border-emerald-500' : 'border-slate-300'
                }`}
              />
              {/* Nodo S3 */}
              <div
                className={`w-5 h-5 rounded-full bg-white border-4 -mr-1 ${
                  sensor.sectorActual === 3 ? 'border-emerald-500' : 'border-slate-300'
                }`}
              />
            </div>

            <div className="flex justify-between text-xs font-semibold text-slate-400 mt-2">
              <span>Inicio</span>
              <span>S2</span>
              <span>S3</span>
            </div>
          </div>
        </div>

        {/* 4 Tarjetas de Estado Rápido */}
        <div className="grid grid-cols-4 gap-2">
          <div className="bg-white rounded-2xl p-3 text-center border border-slate-100 shadow-sm">
            <div className="text-xl mb-1">🚿</div>
            <p className="text-[10px] font-bold text-slate-400 uppercase">BOMBA</p>
            <p className="text-[13px] font-extrabold text-blue-600 mt-0.5">
              {sensor.riegoActivo ? 'ACTIVA' : 'INACTIVA'}
            </p>
          </div>

          <div className="bg-white rounded-2xl p-3 text-center border border-slate-100 shadow-sm">
            <div className="text-xl mb-1">🌾</div>
            <p className="text-[10px] font-bold text-slate-400 uppercase">FERTILIZANTE</p>
            <p className="text-[12px] font-extrabold text-amber-800 mt-0.5">
              {sensor.fertilizacionActiva ? 'ACTIVO' : 'EN ESPERA'}
            </p>
          </div>

          <div className="bg-white rounded-2xl p-3 text-center border border-slate-100 shadow-sm">
            <div className="text-xl mb-1">💧</div>
            <p className="text-[10px] font-bold text-slate-400 uppercase">GLOBAL</p>
            <p className="text-[14px] font-extrabold text-slate-900 mt-0.5">
              {sensor.humedadPorcentaje}%
            </p>
          </div>

          <div className="bg-white rounded-2xl p-3 text-center border border-slate-100 shadow-sm">
            <div className="text-xl mb-1">🤖</div>
            <p className="text-[10px] font-bold text-slate-400 uppercase">ROBOT</p>
            <p className="text-[12px] font-extrabold text-emerald-700 mt-0.5">EN RUTA</p>
          </div>
        </div>

        {/* Tarjeta Alerta Sector Seco */}
        <div className="bg-white rounded-2xl p-4 border border-slate-100 shadow-sm flex items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <div className="w-11 h-11 rounded-2xl bg-red-50 flex items-center justify-center text-xl shrink-0">
              🌵
            </div>
            <div>
              <h3 className="text-[15px] font-bold text-slate-900">
                Sector {sectorSeco.id} necesita riego
              </h3>
              <p className="text-[13px] text-slate-500 font-medium">
                Por debajo del umbral SECO (35%)
              </p>
            </div>
          </div>

          <button
            type="button"
            onClick={() => regarSectorAhora(sectorSeco.id)}
            className="text-emerald-600 font-extrabold text-[15px] px-3 py-2 rounded-xl hover:bg-emerald-50 shrink-0"
          >
            Regar
          </button>
        </div>
      </div>
    </div>
  );
};
