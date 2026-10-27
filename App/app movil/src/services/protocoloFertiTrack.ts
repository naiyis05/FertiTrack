/**
 * Migración exacta del parser de WiFiService.cs y EsclavoService.cs
 */
import {
  SensorData,
  calcularHumedadPorcentaje,
  clasificarEstadoPorHumedad,
  EstadoSueloTipo,
} from '../types/sensor';

export function crearSensorDataInicial(): SensorData {
  const adcInicial = 3383; // ~47% humedad (como en las capturas: 47% / ADC 2873)
  return {
    valorADC: 2873,
    estadoSuelo: 'MEDIA',
    numeroLectura: 128,
    fertilizacionActiva: false,
    cicloCompleto: false,
    esperandoFertilizar: true,
    riegoActivo: true,
    sectorActual: 2,
    puntoSensado: 2,
    estadoMaquina: '📍 Sector 2 / 3 · Riego en curso',
    timestamp: new Date().toLocaleTimeString('es-CO', { hour: '2-digit', minute: '2-digit' }),
    humedadPorcentaje: 47,
  };
}

/**
 * Equivale a WiFiService.ProcesarLinea(string linea) en la app de escritorio
 */
export function procesarLineaMaestro(linea: string, estadoPrevio: SensorData): SensorData {
  const limpio = linea.trim();
  if (!limpio) return estadoPrevio;

  const nuevo: SensorData = {
    ...estadoPrevio,
    timestamp: new Date().toLocaleTimeString('es-CO', { hour: '2-digit', minute: '2-digit' }),
  };

  const partes = limpio.split('|');

  for (const parte of partes) {
    const idx = parte.indexOf(':');
    if (idx < 0) continue;
    const clave = parte.slice(0, idx).toUpperCase().trim();
    const valor = parte.slice(idx + 1).trim();

    switch (clave) {
      case 'ADC': {
        const adc = parseInt(valor, 10);
        if (!isNaN(adc)) {
          nuevo.valorADC = adc;
          nuevo.humedadPorcentaje = calcularHumedadPorcentaje(adc);
        }
        break;
      }

      case 'ESTADO': {
        const v = valor.toUpperCase();
        if (v === 'SECO' || v === 'HUMEDO' || v === 'MEDIA') {
          nuevo.estadoSuelo = v as EstadoSueloTipo;
        }
        break;
      }

      case 'LECTURA': {
        const lec = parseInt(valor, 10);
        if (!isNaN(lec)) nuevo.numeroLectura = lec;
        break;
      }

      case 'SISTEMA': {
        switch (valor) {
          case 'CONECTADO':
            nuevo.estadoMaquina = '🟢 Maestro conectado';
            break;
          case 'CICLO_RIEGO_INICIO':
          case 'CICLO_INICIADO':
            nuevo.estadoMaquina = '🌱 Ciclo de riego iniciado';
            nuevo.cicloCompleto = false;
            nuevo.esperandoFertilizar = false;
            break;
          case 'CICLO_RIEGO_FIN':
            nuevo.estadoMaquina = '🏁 Ciclo de riego completado';
            nuevo.cicloCompleto = true;
            nuevo.riegoActivo = false;
            break;
          case 'FERTILIZACION_INICIO':
            nuevo.estadoMaquina = '🌿 Fertilización iniciada';
            nuevo.fertilizacionActiva = true;
            nuevo.esperandoFertilizar = false;
            break;
          case 'FERTILIZACION_FIN':
            nuevo.estadoMaquina = '✅ Fertilización completada';
            nuevo.fertilizacionActiva = false;
            break;
          case 'LISTO':
            nuevo.estadoMaquina = '🟢 Sistema listo';
            nuevo.riegoActivo = false;
            nuevo.fertilizacionActiva = false;
            nuevo.cicloCompleto = false;
            break;
          case 'STOP':
            nuevo.estadoMaquina = '🛑 Sistema detenido';
            nuevo.riegoActivo = false;
            nuevo.fertilizacionActiva = false;
            nuevo.esperandoFertilizar = false;
            break;
          default:
            nuevo.estadoMaquina = `ℹ️ ${valor}`;
            break;
        }
        break;
      }

      case 'SECTOR': {
        const sec = parseInt(valor, 10);
        if (!isNaN(sec) && sec >= 1 && sec <= 3) {
          nuevo.sectorActual = sec;
          nuevo.estadoMaquina = `📍 Sector ${sec} / 3`;
        }
        break;
      }

      case 'PUNTO': {
        const pto = parseInt(valor, 10);
        if (!isNaN(pto)) {
          nuevo.puntoSensado = pto;
          nuevo.numeroLectura = pto;
        }
        break;
      }

      case 'RIEGO': {
        switch (valor) {
          case 'INICIADO':
            nuevo.estadoMaquina = '💧 Riego en curso...';
            nuevo.riegoActivo = true;
            break;
          case 'COMPLETADO':
            nuevo.estadoMaquina = '✅ Riego completado';
            nuevo.riegoActivo = false;
            break;
          case 'OMITIDO':
            nuevo.estadoMaquina = '✅ Sector húmedo — sin riego';
            nuevo.riegoActivo = false;
            break;
          case 'INTERRUMPIDO':
            nuevo.estadoMaquina = '🛑 Riego interrumpido';
            nuevo.riegoActivo = false;
            break;
          default:
            nuevo.estadoMaquina = `💧 ${valor}`;
            break;
        }
        break;
      }

      case 'BOMBA': {
        nuevo.riegoActivo = valor === 'ON';
        nuevo.estadoMaquina =
          valor === 'ON' ? '💧 Bomba de riego: ENCENDIDA' : '⏹️ Bomba de riego: APAGADA';
        break;
      }

      case 'FERTI_SECTOR': {
        const fSec = parseInt(valor, 10);
        if (!isNaN(fSec)) {
          nuevo.sectorActual = fSec;
          nuevo.estadoMaquina = `🌿 Fertilizando sector ${fSec} / 3`;
        }
        break;
      }

      case 'FERTI_BOMBA': {
        nuevo.fertilizacionActiva = valor === 'ON';
        nuevo.estadoMaquina =
          valor === 'ON'
            ? '💉 Bomba fertilizante: ENCENDIDA'
            : '⏹️ Bomba fertilizante: APAGADA';
        break;
      }

      case 'ESPERANDO_FERTILIZAR': {
        nuevo.esperandoFertilizar = valor === 'SI';
        if (nuevo.esperandoFertilizar) {
          nuevo.estadoMaquina = '⏳ Esperando confirmación de fertilización';
        }
        break;
      }
    }
  }

  return nuevo;
}

/**
 * Equivale a EsclavoService.Parsear(string linea)
 * Formato: "ADC:XXXX|ESTADO:SECO|LECTURA:N"
 */
export function parsearRespuestaEsclavo(linea: string, estadoPrevio: SensorData): SensorData {
  const actualizado = procesarLineaMaestro(linea, estadoPrevio);
  if (!linea.includes('SISTEMA:')) {
    actualizado.estadoMaquina = `📡 Esclavo — ${actualizado.estadoSuelo}  ADC:${actualizado.valorADC}`;
  }
  if (actualizado.valorADC > 0 && !linea.includes('ESTADO:')) {
    actualizado.estadoSuelo = clasificarEstadoPorHumedad(actualizado.humedadPorcentaje);
  }
  return actualizado;
}
