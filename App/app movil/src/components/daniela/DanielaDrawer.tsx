import React, { useState, useRef, useEffect } from 'react';
import { useFertiTrack } from '../../context/FertiTrackContext';

type EstadoMic = 'inactivo' | 'grabando' | 'sin-permiso';

export const DanielaDrawer: React.FC = () => {
  const { danielaAbierta, setDanielaAbierta, mensajesDaniela, enviarMensajeDaniela } =
    useFertiTrack();

  const [inputTexto, setInputTexto] = useState('');
  const [estadoMic, setEstadoMic] = useState<EstadoMic>('inactivo');
  const chatEndRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (danielaAbierta) {
      chatEndRef.current?.scrollIntoView({ behavior: 'smooth' });
    }
  }, [mensajesDaniela, danielaAbierta]);

  if (!danielaAbierta) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!inputTexto.trim()) return;
    const texto = inputTexto;
    setInputTexto('');
    await enviarMensajeDaniela(texto, false);
  };

  const toggleGrabacionVoz = () => {
    if (estadoMic === 'grabando') {
      setEstadoMic('inactivo');
      return;
    }

    const SpeechRecognition =
      (window as unknown as { SpeechRecognition?: any; webkitSpeechRecognition?: any })
        .SpeechRecognition ||
      (window as unknown as { webkitSpeechRecognition?: any }).webkitSpeechRecognition;

    if (!SpeechRecognition) {
      // Simular dictado por voz si el navegador no soporta SpeechRecognition
      setEstadoMic('grabando');
      setTimeout(() => {
        setEstadoMic('inactivo');
        enviarMensajeDaniela('Riega el sector 3 ahora', true);
      }, 1800);
      return;
    }

    try {
      const recognition = new SpeechRecognition();
      recognition.lang = 'es-CO';
      recognition.interimResults = false;
      recognition.maxAlternatives = 1;

      setEstadoMic('grabando');

      recognition.onresult = (event: any) => {
        const transcript = event.results[0][0].transcript;
        setEstadoMic('inactivo');
        if (transcript) {
          enviarMensajeDaniela(transcript, true);
        }
      };

      recognition.onerror = () => {
        setEstadoMic('sin-permiso');
      };

      recognition.onend = () => {
        setEstadoMic((prev) => (prev === 'grabando' ? 'inactivo' : prev));
      };

      recognition.start();
    } catch {
      setEstadoMic('sin-permiso');
    }
  };

  return (
    <div className="fixed inset-0 z-50 bg-slate-900/45 backdrop-blur-[2px] flex flex-col justify-end">
      <div className="w-full max-w-md mx-auto h-[88dvh] bg-[#F5F8F6] rounded-t-[32px] shadow-2xl flex flex-col overflow-hidden">
        {/* Cabecera Verde Daniela */}
        <div className="bg-[#189548] text-white px-5 pt-3 pb-4 shrink-0">
          <div className="w-10 h-1 bg-white/40 rounded-full mx-auto mb-3" />
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-12 h-12 rounded-full bg-white flex items-center justify-center text-2xl shadow-sm">
                👩‍🌾
              </div>
              <div>
                <h2 className="text-[20px] font-extrabold leading-tight">Daniela</h2>
                <p className="text-xs font-semibold text-emerald-100 flex items-center gap-1.5">
                  <span className="w-2 h-2 rounded-full bg-emerald-300 inline-block" />
                  Asistente de FertiTrack · en línea
                </p>
              </div>
            </div>

            <button
              type="button"
              onClick={() => setDanielaAbierta(false)}
              className="w-9 h-9 rounded-full bg-white/20 hover:bg-white/30 flex items-center justify-center text-lg font-bold"
              aria-label="Cerrar asistente"
            >
              ✕
            </button>
          </div>
        </div>

        {/* Lista de mensajes */}
        <div className="flex-1 scroll-container px-4 py-4 space-y-4">
          <div className="text-center">
            <span className="inline-block px-3 py-1 rounded-full bg-slate-200/70 text-slate-500 text-xs font-semibold">
              Hoy · 09:36
            </span>
          </div>

          {mensajesDaniela.map((msg) => {
            const esUsuario = msg.autor === 'usuario';
            return (
              <div
                key={msg.id}
                className={`flex flex-col ${esUsuario ? 'items-end' : 'items-start'}`}
              >
                <div
                  className={`max-w-[85%] rounded-3xl px-4 py-3.5 text-[15px] leading-relaxed shadow-sm ${
                    esUsuario
                      ? 'bg-[#1DB954] text-white font-semibold rounded-br-lg'
                      : 'bg-white text-slate-800 border border-slate-100 rounded-bl-lg'
                  }`}
                >
                  <p>{msg.texto}</p>

                  {/* Tarjeta embebida del sector */}
                  {msg.tarjetaSector && (
                    <div className="mt-3 bg-emerald-50/90 border border-emerald-200 rounded-2xl p-3 flex items-center gap-2.5 text-left">
                      <span className="text-xl">🌵</span>
                      <div>
                        <p className="text-[13.5px] font-extrabold text-emerald-900">
                          Sector {msg.tarjetaSector.sector} · {msg.tarjetaSector.humedad}% ·{' '}
                          {msg.tarjetaSector.estado}
                        </p>
                        <p className="text-xs text-emerald-700 font-medium">
                          Último riego {msg.tarjetaSector.ultimoRiego}
                        </p>
                      </div>
                    </div>
                  )}
                </div>

                <div className="flex items-center gap-1.5 mt-1 px-1">
                  {!esUsuario && (
                    <span className="w-5 h-5 rounded-full bg-emerald-100 flex items-center justify-center text-xs">
                      👩‍🌾
                    </span>
                  )}
                  <span className="text-[11px] text-slate-400 font-medium">
                    {msg.hora}
                    {msg.porVoz ? ' · por voz' : ''}
                  </span>
                </div>
              </div>
            );
          })}
          <div ref={chatEndRef} />
        </div>

        {/* Barra de entrada por voz y texto */}
        <div className="bg-white border-t border-slate-100 px-4 pt-3 pb-4 space-y-3 shrink-0">
          <form onSubmit={handleSubmit} className="flex items-center gap-2.5">
            <button
              type="button"
              onClick={toggleGrabacionVoz}
              className={`w-12 h-12 rounded-full flex items-center justify-center text-xl shrink-0 shadow-sm ${
                estadoMic === 'grabando'
                  ? 'bg-red-500 text-white ring-4 ring-red-100 animate-pulse'
                  : estadoMic === 'sin-permiso'
                  ? 'bg-slate-200 text-slate-500'
                  : 'bg-emerald-500 text-white'
              }`}
              title="Hablar con Daniela"
            >
              🎤
            </button>

            <input
              type="text"
              value={inputTexto}
              onChange={(e) => setInputTexto(e.target.value)}
              placeholder={
                estadoMic === 'grabando'
                  ? 'Escuchando... habla ahora'
                  : 'Pregúntale a Daniela o pide regar...'
              }
              className="flex-1 bg-slate-100 rounded-full px-4 py-3 text-[15px] text-slate-800 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-emerald-500"
            />

            <button
              type="submit"
              className="w-12 h-12 rounded-full bg-[#1DB954] text-white flex items-center justify-center text-lg font-bold shrink-0 shadow-sm"
              aria-label="Enviar mensaje"
            >
              ➤
            </button>
          </form>

          {/* Leyenda de ESTADOS DEL MIC */}
          <div className="flex items-center justify-between text-[11px] text-slate-500 pt-1 border-t border-slate-100">
            <span className="font-bold uppercase tracking-wider text-slate-400">
              ESTADOS DEL MIC
            </span>
            <div className="flex items-center gap-3">
              <button
                type="button"
                onClick={() => setEstadoMic('inactivo')}
                className="inline-flex items-center gap-1"
              >
                <span className="w-4 h-4 rounded-full bg-emerald-500 text-white flex items-center justify-center text-[9px]">
                  🎤
                </span>
                <span>Inactivo</span>
              </button>
              <button
                type="button"
                onClick={() => setEstadoMic('grabando')}
                className="inline-flex items-center gap-1"
              >
                <span className="w-4 h-4 rounded-full bg-red-500 text-white flex items-center justify-center text-[9px]">
                  🎤
                </span>
                <span>Grabando</span>
              </button>
              <button
                type="button"
                onClick={() => setEstadoMic('sin-permiso')}
                className="inline-flex items-center gap-1"
              >
                <span className="w-4 h-4 rounded-full bg-slate-200 text-slate-500 flex items-center justify-center text-[9px]">
                  🎤
                </span>
                <span>Sin permiso</span>
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
