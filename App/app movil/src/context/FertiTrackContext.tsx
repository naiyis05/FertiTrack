import React, { createContext, useContext, useState, useEffect, useRef, useCallback } from 'react';
import {
  SensorData,
  SectorInfo,
  EventoRiego,
  PuntoTendencia,
  MensajeDaniela,
  PestanaActiva,
  calcularHumedadPorcentaje,
  clasificarEstadoPorHumedad,
} from '../types/sensor';
import {
  crearSensorDataInicial,
  procesarLineaMaestro,
  parsearRespuestaEsclavo,
} from '../services/protocoloFertiTrack';
import { consultarDanielaOmniRoute } from '../services/omniRouteDanielaService';

interface FertiTrackContextType {
  pestanaActiva: PestanaActiva;
  setPestanaActiva: (p: PestanaActiva) => void;

  // Estado SensorData (Equivalente a MainViewModel.cs)
  sensor: SensorData;
  conectadoMaestro: boolean;
  ipMaestro: string;
  setIpMaestro: (ip: string) => void;
  mensajeErrorMaestro: string;

  esclavoConectado: boolean;
  ipEsclavo: string;
  setIpEsclavo: (ip: string) => void;
  mensajeErrorEsclavo: string;

  riegoAutomatico: boolean;
  setRiegoAutomatico: (v: boolean) => void;
  umbralSecoPct: number;

  mostrarAlertaFertilizacion: boolean;
  conteoRiegos: number;
  isNight: boolean;
  setIsNight: (v: boolean) => void;
  franjaHorariaIdx: number;
  setFranjaHorariaIdx: (idx: number) => void;

  sectores: SectorInfo[];
  eventosRiego: EventoRiego[];
  bitacoraEventos: string[];
  tendenciaHumedad: PuntoTendencia[];
  rangoGrafica: '1h' | '6h' | '24h';
  setRangoGrafica: (r: '1h' | '6h' | '24h') => void;

  // Insight de Análisis
  insightActivo: boolean;
  descartarInsight: () => void;
  aplicarSugerenciaInsight: () => void;

  // Daniela Drawer
  danielaAbierta: boolean;
  setDanielaAbierta: (v: boolean) => void;
  notificacionesDaniela: number;
  mensajesDaniela: MensajeDaniela[];
  enviarMensajeDaniela: (texto: string, porVoz?: boolean) => Promise<void>;

  // Acciones de control (Equivalentes a MainViewModel.cs)
  toggleConexionMaestro: () => void;
  toggleConexionEsclavo: () => void;
  confirmarFertilizacion: () => void;
  ignorarFertilizacion: () => void;
  fertilizarManualmente: () => Promise<void>;
  regarSectorAhora: (sectorId: 1 | 2 | 3) => void;
  seleccionarSectorActivo: (sectorId: 1 | 2 | 3) => void;
  exportarBitacoraCSV: () => void;
  detenerCicloEmergencia: () => void;
  modoSimulacion: boolean;
  setModoSimulacion: (v: boolean) => void;
}

const FertiTrackContext = createContext<FertiTrackContextType | null>(null);

const TENDENCIA_INICIAL: PuntoTendencia[] = [
  { hora: '08:40', humedad: 76, valorADC: 3064 },
  { hora: '08:50', humedad: 80, valorADC: 3020 },
  { hora: '09:00', humedad: 65, valorADC: 3185 },
  { hora: '09:10', humedad: 70, valorADC: 3130 },
  { hora: '09:20', humedad: 51, valorADC: 3339 },
  { hora: '09:30', humedad: 56, valorADC: 3284 },
  { hora: '09:36', humedad: 42, valorADC: 3438 },
  { hora: '09:41', humedad: 47, valorADC: 2873 },
];

const SECTORES_INICIALES: SectorInfo[] = [
  {
    id: 1,
    nombre: 'SECTOR 1',
    humedad: 68,
    valorADC: 3152,
    estado: 'HUMEDO',
    ultimoRiego: '08:15',
    puntos: [70, 66, 68],
  },
  {
    id: 2,
    nombre: 'SECTOR 2',
    humedad: 47,
    valorADC: 2873,
    estado: 'MEDIA',
    ultimoRiego: '09:38',
    puntos: [50, 47, 44],
  },
  {
    id: 3,
    nombre: 'SECTOR 3',
    humedad: 24,
    valorADC: 3120,
    estado: 'SECO',
    ultimoRiego: '07:40',
    puntos: [26, 24, 22],
  },
];

const EVENTOS_RIEGO_INICIALES: EventoRiego[] = [
  {
    id: 'ev-1',
    sector: 2,
    punto: 2,
    hora: '09:38',
    duracionSegundos: 12,
    valorADC: 2873,
    humedad: 47,
    descripcion: 'Sector 2 · punto 2',
  },
  {
    id: 'ev-2',
    sector: 1,
    punto: 3,
    hora: '08:52',
    duracionSegundos: 10,
    valorADC: 3152,
    humedad: 68,
    descripcion: 'Sector 1 · punto 3',
  },
  {
    id: 'ev-3',
    sector: 3,
    punto: 1,
    hora: '07:40',
    duracionSegundos: 15,
    valorADC: 3120,
    humedad: 24,
    descripcion: 'Sector 3 · punto 1',
  },
];

const MENSAJES_DANIELA_INICIALES: MensajeDaniela[] = [
  {
    id: 'm-1',
    autor: 'daniela',
    texto: 'Hola 👋 El invernadero está en ciclo. ¿Quieres que revise algún sector?',
    hora: '09:36',
  },
  {
    id: 'm-2',
    autor: 'usuario',
    texto: '¿Por qué el sector 3 está tan seco?',
    hora: '09:37',
  },
  {
    id: 'm-3',
    autor: 'daniela',
    texto:
      'Su última lectura fue 24% (ADC 3120), por debajo del umbral SECO. El robot no ha llegado ahí desde las 07:40 porque el ciclo se detuvo esperando confirmación de fertilización.',
    hora: '09:37',
    tarjetaSector: {
      sector: 3,
      humedad: 24,
      estado: 'SECO',
      ultimoRiego: '07:40',
    },
  },
  {
    id: 'm-4',
    autor: 'usuario',
    texto: 'Riega el sector 3 ahora',
    hora: '09:38',
    porVoz: true,
  },
];

export const FertiTrackProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [pestanaActiva, setPestanaActiva] = useState<PestanaActiva>('Dashboard');
  const [sensor, setSensor] = useState<SensorData>(crearSensorDataInicial);

  // Conexiones como en las imágenes de referencia y MainViewModel.cs
  const [conectadoMaestro, setConectadoMaestro] = useState<boolean>(true);
  const [ipMaestro, setIpMaestro] = useState<string>('192.168.1.50');
  const [mensajeErrorMaestro, setMensajeErrorMaestro] = useState<string>('');

  const [esclavoConectado, setEsclavoConectado] = useState<boolean>(false);
  const [ipEsclavo, setIpEsclavo] = useState<string>('192.168.1.7');
  const [mensajeErrorEsclavo, setMensajeErrorEsclavo] = useState<string>(
    'No se pudo alcanzar el host. Verifica que el ESP32 esté en la misma red.'
  );

  const [riegoAutomatico, setRiegoAutomatico] = useState<boolean>(true);
  const [umbralSecoPct] = useState<number>(35);
  const [mostrarAlertaFertilizacion, setMostrarAlertaFertilizacion] = useState<boolean>(true);
  const [conteoRiegos, setConteoRiegos] = useState<number>(3);

  const horaActual = new Date().getHours();
  const [isNight, setIsNight] = useState<boolean>(false);
  const [franjaHorariaIdx, setFranjaHorariaIdx] = useState<number>(3); // Mañana soleada por defecto

  const [sectores, setSectores] = useState<SectorInfo[]>(SECTORES_INICIALES);
  const [eventosRiego, setEventosRiego] = useState<EventoRiego[]>(EVENTOS_RIEGO_INICIALES);
  const [bitacoraEventos, setBitacoraEventos] = useState<string[]>([
    '[09:41:00] 📡 Lectura #128 — ADC: 2873 — MEDIA (47%)',
    '[09:38:12] ⚠️ Ciclo completado — ¿Desea fertilizar Sector 2?',
    '[09:38:00] 💧 Sector 2 · punto 2 regado (12 s de bomba)',
    '[09:10:00] 📍 Maestro avanzó a Sector 2 / 3',
  ]);
  const [tendenciaHumedad, setTendenciaHumedad] = useState<PuntoTendencia[]>(TENDENCIA_INICIAL);
  const [rangoGrafica, setRangoGrafica] = useState<'1h' | '6h' | '24h'>('1h');

  const [insightActivo, setInsightActivo] = useState<boolean>(true);
  const [danielaAbierta, setDanielaAbierta] = useState<boolean>(false);
  const [notificacionesDaniela, setNotificacionesDaniela] = useState<number>(1);
  const [mensajesDaniela, setMensajesDaniela] = useState<MensajeDaniela[]>(
    MENSAJES_DANIELA_INICIALES
  );
  const [modoSimulacion, setModoSimulacion] = useState<boolean>(true);

  const wsRef = useRef<WebSocket | null>(null);
  const esclavoTimerRef = useRef<number | null>(null);

  const agregarBitacora = useCallback((mensaje: string) => {
    const hora = new Date().toLocaleTimeString('es-CO', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
    setBitacoraEventos((prev) => [`[${hora}] ${mensaje}`, ...prev.slice(0, 29)]);
  }, []);

  // Conexión WebSocket opcional al Bridge Node.js (para hablar con Maestro TCP :8888)
  useEffect(() => {
    try {
      const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
      const wsUrl = `${proto}://${window.location.host}/ws`;
      const ws = new WebSocket(wsUrl);
      wsRef.current = ws;

      ws.onmessage = (ev) => {
        try {
          const msg = JSON.parse(ev.data);
          if (msg.type === 'MAESTRO_LINE') {
            setSensor((prev) => {
              const next = procesarLineaMaestro(msg.linea, prev);
              if (next.esperandoFertilizar) setMostrarAlertaFertilizacion(true);
              if (next.cicloCompleto && !prev.cicloCompleto) {
                setConteoRiegos((c) => c + 1);
              }
              return next;
            });
          } else if (msg.type === 'MAESTRO_ERROR') {
            setMensajeErrorMaestro(msg.error);
          }
        } catch {}
      };
      return () => ws.close();
    } catch {
      return undefined;
    }
  }, []);

  // Polling HTTP al Esclavo cada 3 segundos cuando esclavoConectado == true (igual que EsclavoService.cs)
  useEffect(() => {
    if (!esclavoConectado) {
      if (esclavoTimerRef.current) window.clearInterval(esclavoTimerRef.current);
      return;
    }

    const consultarEsclavo = async () => {
      try {
        const res = await fetch(`/api/esclavo/leer?ip=${encodeURIComponent(ipEsclavo)}`);
        if (!res.ok) throw new Error('Sin señal del ESP32');
        const data = await res.json();
        if (data.ok && data.raw) {
          setMensajeErrorEsclavo('');
          setSensor((prev) => {
            const actualizado = parsearRespuestaEsclavo(data.raw, prev);
            const horaCorta = new Date().toLocaleTimeString('es-CO', {
              hour: '2-digit',
              minute: '2-digit',
            });
            setTendenciaHumedad((t) => [
              ...t.slice(-15),
              {
                hora: horaCorta,
                humedad: actualizado.humedadPorcentaje,
                valorADC: actualizado.valorADC,
              },
            ]);
            return actualizado;
          });
        }
      } catch {
        if (!modoSimulacion) {
          setMensajeErrorEsclavo(
            'No se pudo alcanzar el host. Verifica que el ESP32 esté en la misma red.'
          );
        }
      }
    };

    consultarEsclavo();
    esclavoTimerRef.current = window.setInterval(consultarEsclavo, 3000);
    return () => {
      if (esclavoTimerRef.current) window.clearInterval(esclavoTimerRef.current);
    };
  }, [esclavoConectado, ipEsclavo, modoSimulacion]);

  // Pulso suave en modo simulación cada 6 segundos para mantener viva la telemetría
  useEffect(() => {
    if (!modoSimulacion) return;
    const id = window.setInterval(() => {
      setSensor((prev) => {
        const deltaADC = Math.floor(Math.random() * 25) - 12;
        const nuevoADC = Math.max(2800, Math.min(3900, prev.valorADC + deltaADC));
        return {
          ...prev,
          valorADC: nuevoADC,
          numeroLectura: prev.numeroLectura + 1,
          timestamp: new Date().toLocaleTimeString('es-CO', {
            hour: '2-digit',
            minute: '2-digit',
          }),
        };
      });
    }, 6000);
    return () => window.clearInterval(id);
  }, [modoSimulacion]);

  const toggleConexionMaestro = () => {
    if (!conectadoMaestro) {
      if (!ipMaestro.trim()) {
        setMensajeErrorMaestro('Ingresa la IP del Maestro (ej: 192.168.1.50)');
        return;
      }
      setConectadoMaestro(true);
      setMensajeErrorMaestro('');
      agregarBitacora(`📡 Maestro conectado ${ipMaestro}:8888`);
      if (wsRef.current?.readyState === 1) {
        wsRef.current.send(
          JSON.stringify({ action: 'CONECTAR_MAESTRO', ip: ipMaestro.trim(), puerto: 8888 })
        );
      }
    } else {
      setConectadoMaestro(false);
      setSensor((prev) => ({ ...prev, estadoMaquina: '⏸ Maestro desconectado' }));
      agregarBitacora('🔌 Maestro desconectado');
      if (wsRef.current?.readyState === 1) {
        wsRef.current.send(JSON.stringify({ action: 'DESCONECTAR_MAESTRO' }));
      }
    }
  };

  const toggleConexionEsclavo = () => {
    if (!esclavoConectado) {
      if (!ipEsclavo.trim()) {
        setMensajeErrorEsclavo('Ingresa la IP del Esclavo (ej: 192.168.1.7)');
        return;
      }
      setEsclavoConectado(true);
      setMensajeErrorEsclavo('');
      agregarBitacora(`🌱 Esclavo conectado ${ipEsclavo} — polling cada 3 s`);
    } else {
      setEsclavoConectado(false);
      agregarBitacora('🔌 Esclavo desconectado');
    }
  };

  const confirmarFertilizacion = () => {
    setMostrarAlertaFertilizacion(false);
    setSensor((prev) => ({
      ...prev,
      esperandoFertilizar: false,
      fertilizacionActiva: true,
      estadoMaquina: `🌿 Fertilizando Sector ${prev.sectorActual} / 3`,
    }));
    agregarBitacora(`🌱 Fertilización confirmada en Sector ${sensor.sectorActual}`);
    if (wsRef.current?.readyState === 1) {
      wsRef.current.send(JSON.stringify({ action: 'ENVIAR_COMANDO_MAESTRO', comando: 'S' }));
    }
  };

  const ignorarFertilizacion = () => {
    setMostrarAlertaFertilizacion(false);
    setSensor((prev) => ({
      ...prev,
      esperandoFertilizar: false,
      estadoMaquina: '🟢 Ciclo continúa sin fertilización',
    }));
    agregarBitacora('🚫 Alerta de fertilización ignorada por el usuario');
  };

  const fertilizarManualmente = async () => {
    setSensor((prev) => ({
      ...prev,
      fertilizacionActiva: true,
      estadoMaquina: `💉 Dosis única de fertilizante en Sector ${prev.sectorActual}`,
    }));
    agregarBitacora(`💉 Fertilización manual activada en Sector ${sensor.sectorActual}`);
    try {
      await fetch(`/api/esclavo/fertilizar?ip=${encodeURIComponent(ipEsclavo)}&on=true`);
    } catch {}
  };

  const regarSectorAhora = (sectorId: 1 | 2 | 3) => {
    const hora = new Date().toLocaleTimeString('es-CO', {
      hour: '2-digit',
      minute: '2-digit',
    });
    setConteoRiegos((c) => c + 1);
    setSensor((prev) => ({
      ...prev,
      sectorActual: sectorId,
      riegoActivo: true,
      humedadPorcentaje: sectorId === 3 ? 48 : prev.humedadPorcentaje,
      estadoSuelo: 'MEDIA',
      estadoMaquina: `💧 Regando Sector ${sectorId} en curso...`,
    }));
    setSectores((prev) =>
      prev.map((s) =>
        s.id === sectorId
          ? {
              ...s,
              humedad: Math.min(72, s.humedad + 24),
              estado: clasificarEstadoPorHumedad(Math.min(72, s.humedad + 24)),
              ultimoRiego: hora,
            }
          : s
      )
    );
    setEventosRiego((prev) => [
      {
        id: `ev-${Date.now()}`,
        sector: sectorId,
        punto: 1,
        hora,
        duracionSegundos: 14,
        valorADC: 3120,
        humedad: 48,
        descripcion: `Sector ${sectorId} · punto 1`,
      },
      ...prev,
    ]);
    agregarBitacora(`💧 Riego manual iniciado en Sector ${sectorId}`);
  };

  const seleccionarSectorActivo = (sectorId: 1 | 2 | 3) => {
    const sec = sectores.find((s) => s.id === sectorId);
    if (!sec) return;
    setSensor((prev) => ({
      ...prev,
      sectorActual: sectorId,
      humedadPorcentaje: sec.humedad,
      valorADC: sec.valorADC,
      estadoSuelo: sec.estado,
      estadoMaquina: `📍 Sector ${sectorId} / 3 seleccionado`,
    }));
  };

  const descartarInsight = () => {
    setInsightActivo(false);
    agregarBitacora('ℹ️ Sugerencia del sistema descartada');
  };

  const aplicarSugerenciaInsight = () => {
    regarSectorAhora(3);
    setInsightActivo(false);
    agregarBitacora('🧠 Sugerencia aplicada: Riego adelantado en Sector 3 + fertilización programada');
  };

  const exportarBitacoraCSV = () => {
    const encabezados = 'FechaHora,Sector,Punto,ValorADC,HumedadPorcentaje,EstadoSuelo,Evento\n';
    const filasEventos = eventosRiego
      .map(
        (ev) =>
          `${ev.hora},${ev.sector},${ev.punto},${ev.valorADC},${ev.humedad}%,${clasificarEstadoPorHumedad(ev.humedad)},"${ev.descripcion} (${ev.duracionSegundos}s)"`
      )
      .join('\n');
    const filaActual = `\n${sensor.timestamp},${sensor.sectorActual},${sensor.puntoSensado},${sensor.valorADC},${sensor.humedadPorcentaje}%,${sensor.estadoSuelo},"${sensor.estadoMaquina}"`;
    const contenido = encabezados + filasEventos + filaActual;

    const blob = new Blob([contenido], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.setAttribute('download', `FertiTrack_Bitacora_${new Date().toISOString().slice(0, 10)}.csv`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
    agregarBitacora('📥 Bitácora CSV exportada correctamente');
  };

  const detenerCicloEmergencia = () => {
    setSensor((prev) => ({
      ...prev,
      riegoActivo: false,
      fertilizacionActiva: false,
      estadoMaquina: '🛑 Sistema detenido por operador',
    }));
    agregarBitacora('🛑 Parada de ciclo ejecutada desde menú secundario');
  };

  const enviarMensajeDaniela = async (texto: string, porVoz = false) => {
    if (!texto.trim()) return;
    const hora = new Date().toLocaleTimeString('es-CO', {
      hour: '2-digit',
      minute: '2-digit',
    });

    const msgUsuario: MensajeDaniela = {
      id: `u-${Date.now()}`,
      autor: 'usuario',
      texto: texto.trim(),
      hora,
      porVoz,
    };

    setMensajesDaniela((prev) => [...prev, msgUsuario]);

    const respuesta = await consultarDanielaOmniRoute(texto, sensor, sectores);

    if (respuesta.accionEjecutada?.tipo === 'REGAR_SECTOR' && respuesta.accionEjecutada.sector) {
      regarSectorAhora(respuesta.accionEjecutada.sector);
    } else if (respuesta.accionEjecutada?.tipo === 'FERTILIZAR') {
      await fertilizarManualmente();
    }

    const msgDaniela: MensajeDaniela = {
      id: `d-${Date.now() + 1}`,
      autor: 'daniela',
      texto: respuesta.texto,
      hora: new Date().toLocaleTimeString('es-CO', {
        hour: '2-digit',
        minute: '2-digit',
      }),
      tarjetaSector: respuesta.tarjetaSector,
    };

    setMensajesDaniela((prev) => [...prev, msgDaniela]);
  };

  // Ajustar modo noche si se selecciona un ícono nocturno en la barra solar
  useEffect(() => {
    setIsNight(franjaHorariaIdx === 0 || franjaHorariaIdx === 1 || franjaHorariaIdx >= 6);
  }, [franjaHorariaIdx, horaActual]);

  return (
    <FertiTrackContext.Provider
      value={{
        pestanaActiva,
        setPestanaActiva,
        sensor,
        conectadoMaestro,
        ipMaestro,
        setIpMaestro,
        mensajeErrorMaestro,
        esclavoConectado,
        ipEsclavo,
        setIpEsclavo,
        mensajeErrorEsclavo,
        riegoAutomatico,
        setRiegoAutomatico,
        umbralSecoPct,
        mostrarAlertaFertilizacion,
        conteoRiegos,
        isNight,
        setIsNight,
        franjaHorariaIdx,
        setFranjaHorariaIdx,
        sectores,
        eventosRiego,
        bitacoraEventos,
        tendenciaHumedad,
        rangoGrafica,
        setRangoGrafica,
        insightActivo,
        descartarInsight,
        aplicarSugerenciaInsight,
        danielaAbierta,
        setDanielaAbierta: (v: boolean) => {
          setDanielaAbierta(v);
          if (v) setNotificacionesDaniela(0);
        },
        notificacionesDaniela,
        mensajesDaniela,
        enviarMensajeDaniela,
        toggleConexionMaestro,
        toggleConexionEsclavo,
        confirmarFertilizacion,
        ignorarFertilizacion,
        fertilizarManualmente,
        regarSectorAhora,
        seleccionarSectorActivo,
        exportarBitacoraCSV,
        detenerCicloEmergencia,
        modoSimulacion,
        setModoSimulacion,
      }}
    >
      {children}
    </FertiTrackContext.Provider>
  );
};

export function useFertiTrack() {
  const ctx = useContext(FertiTrackContext);
  if (!ctx) throw new Error('useFertiTrack debe usarse dentro de FertiTrackProvider');
  return ctx;
}
