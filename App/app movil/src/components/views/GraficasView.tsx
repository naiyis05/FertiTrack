import React from 'react';
import { useFertiTrack } from '../../context/FertiTrackContext';

export const GraficasView: React.FC = () => {
  const {
    sensor,
    tendenciaHumedad,
    rangoGrafica,
    setRangoGrafica,
    eventosRiego,
    conteoRiegos,
  } = useFertiTrack();

  const nivelTexto =
    sensor.humedadPorcentaje >= 60
      ? 'Nivel óptimo'
      : sensor.humedadPorcentaje >= 35
      ? 'Nivel intermedio'
      : 'Nivel seco';

  const colorLiquido =
    sensor.humedadPorcentaje >= 60
      ? 'from-emerald-400 to-emerald-500'
      : sensor.humedadPorcentaje >= 35
      ? 'from-amber-300 to-amber-500'
      : 'from-red-400 to-red-500';

  // Construir coordenadas SVG de la curva de tendencia
  const ancho = 300;
  const alto = 135;
  const puntos = tendenciaHumedad.slice(-8);
  const coords = puntos.map((p, i) => {
    const x = (i / Math.max(1, puntos.length - 1)) * (ancho - 24) + 12;
    const y = alto - (p.humedad / 100) * (alto - 25) - 10;
    return { x, y, ...p };
  });

  const polylineStr = coords.map((c) => `${c.x},${c.y}`).join(' ');
  const areaStr =
    coords.length > 0
      ? `${coords[0].x},${alto} ${polylineStr} ${coords[coords.length - 1].x},${alto}`
      : '';

  const ultimoPunto = coords[coords.length - 1];

  return (
    <div className="px-4 pt-3 pb-28 space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h1 className="text-[28px] font-extrabold tracking-tight text-slate-900">Gráficas</h1>
        <span className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-bold bg-emerald-100/90 text-emerald-700">
          <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
          EN VIVO
        </span>
      </div>

      {/* Tarjeta Tanque de Humedad */}
      <div className="bg-white rounded-3xl p-5 border border-slate-100 shadow-sm flex items-center gap-5">
        {/* Cápsula del Tanque */}
        <div className="relative w-28 h-52 rounded-full bg-slate-100 border-4 border-slate-200/80 overflow-hidden flex items-center justify-center shrink-0 shadow-inner">
          {/* Líquido */}
          <div
            className={`absolute bottom-0 left-0 right-0 bg-gradient-to-b ${colorLiquido} transition-all duration-700`}
            style={{ height: `${sensor.humedadPorcentaje}%` }}
          />
          {/* Marcas de graduación */}
          <div className="absolute left-3 top-1/4 w-3.5 h-0.5 bg-slate-300 rounded-full z-10" />
          <div className="absolute left-3 top-2/4 w-3.5 h-0.5 bg-slate-300 rounded-full z-10" />
          <div className="absolute left-3 top-3/4 w-3.5 h-0.5 bg-white/70 rounded-full z-10" />

          {/* Porcentaje central */}
          <span className="relative z-20 text-[30px] font-extrabold text-slate-900 drop-shadow-sm">
            {sensor.humedadPorcentaje}%
          </span>
        </div>

        {/* Leyenda derecha */}
        <div className="space-y-2.5 flex-1">
          <p className="text-[11px] font-bold tracking-wider text-slate-400 uppercase">
            TANQUE DE HUMEDAD
          </p>
          <h2 className="text-[22px] font-extrabold text-slate-900 leading-tight">
            {nivelTexto}
          </h2>

          <div className="space-y-1.5 pt-1 text-[13.5px]">
            <div className="flex items-center gap-2 text-slate-600">
              <span className="w-3 h-3 rounded-sm bg-emerald-500 inline-block" />
              <span className={sensor.humedadPorcentaje >= 60 ? 'font-bold text-slate-900' : ''}>
                Óptimo ≥ 60%
              </span>
            </div>
            <div className="flex items-center gap-2 text-slate-600">
              <span className="w-3 h-3 rounded-sm bg-amber-500 inline-block" />
              <span
                className={
                  sensor.humedadPorcentaje >= 35 && sensor.humedadPorcentaje < 60
                    ? 'font-bold text-slate-900'
                    : ''
                }
              >
                Intermedio 35–59%
              </span>
            </div>
            <div className="flex items-center gap-2 text-slate-600">
              <span className="w-3 h-3 rounded-sm bg-red-500 inline-block" />
              <span className={sensor.humedadPorcentaje < 35 ? 'font-bold text-slate-900' : ''}>
                Seco &lt; 35%
              </span>
            </div>
          </div>

          <p className="font-mono text-xs text-slate-400 pt-1">ValorADC {sensor.valorADC}</p>
        </div>
      </div>

      {/* Tarjeta Tendencia de humedad */}
      <div className="bg-white rounded-3xl p-5 border border-slate-100 shadow-sm space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="text-[17px] font-bold text-slate-900">Tendencia de humedad</h2>
          <div className="inline-flex bg-slate-100 rounded-xl p-1 text-xs font-bold">
            {(['1h', '6h', '24h'] as const).map((r) => (
              <button
                key={r}
                type="button"
                onClick={() => setRangoGrafica(r)}
                className={`px-2.5 py-1 rounded-lg transition-all ${
                  rangoGrafica === r ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'
                }`}
              >
                {r === '1h' ? '1 h' : r === '6h' ? '6 h' : '24 h'}
              </button>
            ))}
          </div>
        </div>

        {/* Gráfico SVG */}
        <div className="flex items-center gap-2">
          <div className="flex flex-col justify-between h-[135px] text-[11px] font-semibold text-slate-400 py-1">
            <span>100</span>
            <span>75</span>
            <span>50</span>
            <span>25</span>
          </div>

          <div className="flex-1">
            <svg viewBox={`0 0 ${ancho} ${alto}`} className="w-full h-[135px] overflow-visible">
              <defs>
                <linearGradient id="fertiAreaGrad" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#22C55E" stopOpacity="0.38" />
                  <stop offset="100%" stopColor="#22C55E" stopOpacity="0.02" />
                </linearGradient>
              </defs>

              {/* Líneas horizontales guía */}
              {[20, 55, 90, 125].map((yLine) => (
                <line
                  key={yLine}
                  x1="0"
                  y1={yLine}
                  x2={ancho}
                  y2={yLine}
                  stroke="#F1F5F9"
                  strokeWidth="1"
                />
              ))}

              {/* Área rellena */}
              {areaStr && <polygon points={areaStr} fill="url(#fertiAreaGrad)" />}

              {/* Línea principal */}
              {polylineStr && (
                <polyline
                  fill="none"
                  stroke="#22C55E"
                  strokeWidth="3"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  points={polylineStr}
                />
              )}

              {/* Punto final */}
              {ultimoPunto && (
                <circle
                  cx={ultimoPunto.x}
                  cy={ultimoPunto.y}
                  r="5"
                  className="fill-emerald-500 stroke-white stroke-2"
                />
              )}
            </svg>

            <div className="flex justify-between text-[11px] font-semibold text-slate-400 px-2 mt-1">
              <span>{puntos[0]?.hora || '08:40'}</span>
              <span>{puntos[Math.floor(puntos.length / 2)]?.hora || '09:10'}</span>
              <span>{puntos[puntos.length - 1]?.hora || '09:41'}</span>
            </div>
          </div>
        </div>
      </div>

      {/* Sección Eventos de riego */}
      <div className="space-y-2.5">
        <div className="flex items-center justify-between px-1">
          <h2 className="text-[17px] font-bold text-slate-900">Eventos de riego</h2>
          <span className="font-mono text-xs text-slate-400">
            ConteoRiegos · {conteoRiegos}
          </span>
        </div>

        <div className="space-y-2.5">
          {eventosRiego.map((ev) => (
            <div
              key={ev.id}
              className="bg-white rounded-2xl p-4 border border-slate-100 shadow-sm flex items-center gap-3.5"
            >
              <div className="w-11 h-11 rounded-2xl bg-sky-50 flex items-center justify-center text-xl shrink-0">
                🚿
              </div>
              <div>
                <h3 className="text-[15px] font-bold text-slate-900">{ev.descripcion}</h3>
                <p className="text-[13px] text-slate-500 font-medium">
                  {ev.hora} · {ev.duracionSegundos} s de bomba
                </p>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};
