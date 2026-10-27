/**
 * Servicio para Daniela (Asistente de FertiTrack)
 * Conectado a OmniRoute (http://localhost:20128/v1/chat/completions) con
 * fallback inteligente que interpreta el estado real de SensorData y los 3 sectores.
 */
import { SensorData, SectorInfo, MensajeDaniela } from '../types/sensor';

export interface RespuestaDaniela {
  texto: string;
  tarjetaSector?: MensajeDaniela['tarjetaSector'];
  accionEjecutada?: {
    tipo: 'REGAR_SECTOR' | 'FERTILIZAR' | 'NINGUNA';
    sector?: 1 | 2 | 3;
  };
}

const OMNIROUTE_ENDPOINT = 'http://localhost:20128/v1/chat/completions';

export async function consultarDanielaOmniRoute(
  preguntaUsuario: string,
  sensor: SensorData,
  sectores: SectorInfo[]
): Promise<RespuestaDaniela> {
  const textoMin = preguntaUsuario.toLowerCase();

  // Detectar comandos directos de voz/texto ("riega el sector 3 ahora", "fertilizar", etc.)
  if (textoMin.includes('riega') || textoMin.includes('regar')) {
    const matchSec = textoMin.match(/sector\s*([123])/);
    const numSec = (matchSec ? parseInt(matchSec[1], 10) : 3) as 1 | 2 | 3;
    const secInfo = sectores.find((s) => s.id === numSec) || sectores[2];
    return {
      texto: `¡Entendido! Activando el ciclo de riego en el Sector ${numSec} ahora mismo. La bomba se encenderá hasta superar el umbral óptimo (60%).`,
      tarjetaSector: {
        sector: numSec,
        humedad: secInfo.humedad,
        estado: secInfo.estado,
        ultimoRiego: 'Iniciando ahora',
      },
      accionEjecutada: {
        tipo: 'REGAR_SECTOR',
        sector: numSec,
      },
    };
  }

  if (textoMin.includes('fertiliz')) {
    return {
      texto: `Iniciando dosis de fertilización en el Sector ${sensor.sectorActual} activo. Te avisaré apenas la bomba de nutrientes termine el ciclo.`,
      accionEjecutada: {
        tipo: 'FERTILIZAR',
        sector: sensor.sectorActual as 1 | 2 | 3,
      },
    };
  }

  // Intentar consultar el router OmniRoute local si está activo
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 3500);

    const systemPrompt = `Eres Daniela, la asistente inteligente del invernadero automatizado FertiTrack.
Responde en español, breve (máximo 3 oraciones), clara y amigable.
Datos en tiempo real del invernadero:
- Sector activo del robot: Sector ${sensor.sectorActual}, Punto ${sensor.puntoSensado}/3
- Humedad actual: ${sensor.humedadPorcentaje}% (ADC ${sensor.valorADC}, lectura #${sensor.numeroLectura})
- Estado del suelo: ${sensor.estadoSuelo}
- Estado máquina: ${sensor.estadoMaquina}
- Sectores: ${sectores.map((s) => `Sector ${s.id}: ${s.humedad}% (${s.estado}, último riego ${s.ultimoRiego})`).join(' | ')}`;

    const res = await fetch(OMNIROUTE_ENDPOINT, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: 'auto',
        messages: [
          { role: 'system', content: systemPrompt },
          { role: 'user', content: preguntaUsuario },
        ],
        max_tokens: 220,
      }),
      signal: controller.signal,
    });
    clearTimeout(timeout);

    if (res.ok) {
      const data = await res.json();
      const replyText = data?.choices?.[0]?.message?.content?.trim();
      if (replyText) {
        const sec3 = sectores.find((s) => s.id === 3) || sectores[2];
        return {
          texto: replyText,
          tarjetaSector: textoMin.includes('sector 3')
            ? {
                sector: 3,
                humedad: sec3.humedad,
                estado: sec3.estado,
                ultimoRiego: sec3.ultimoRiego,
              }
            : undefined,
        };
      }
    }
  } catch {
    // OmniRoute offline -> respuesta contextual inteligente local
  }

  // Respuesta contextual local idéntica al comportamiento del diseño
  if (textoMin.includes('sector 3') || textoMin.includes('seco')) {
    const sec3 = sectores.find((s) => s.id === 3) || sectores[2];
    return {
      texto: `Su última lectura fue ${sec3.humedad}% (ADC ${sec3.valorADC}), por debajo del umbral SECO. El robot no ha llegado ahí desde las ${sec3.ultimoRiego} porque el ciclo se detuvo esperando confirmación de fertilización.`,
      tarjetaSector: {
        sector: 3,
        humedad: sec3.humedad,
        estado: sec3.estado,
        ultimoRiego: sec3.ultimoRiego,
      },
    };
  }

  if (textoMin.includes('sector 1')) {
    const sec1 = sectores[0];
    return {
      texto: `El Sector 1 está en nivel óptimo con ${sec1.humedad}% de humedad (ADC ${sec1.valorADC}). No requiere riego adicional por ahora.`,
      tarjetaSector: {
        sector: 1,
        humedad: sec1.humedad,
        estado: sec1.estado,
        ultimoRiego: sec1.ultimoRiego,
      },
    };
  }

  return {
    texto: `El invernadero reporta ${sensor.humedadPorcentaje}% de humedad global (ADC ${sensor.valorADC}, lectura #${sensor.numeroLectura}). El robot se encuentra en el Sector ${sensor.sectorActual} (Punto ${sensor.puntoSensado}/3). ¿Quieres que riegue el Sector 3 o aplique fertilizante?`,
  };
}
