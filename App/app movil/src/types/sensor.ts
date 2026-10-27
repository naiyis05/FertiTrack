/**
 * Migración exacta de InvernaderoApp.Models.SensorData (C# WPF -> TypeScript)
 * Calibración física del sensor capacitivo en ESP32:
 *   0% humedad = ADC 3900 (seco)
 *   100% humedad = ADC 2800 (agua)
 */

export type EstadoSueloTipo = 'SECO' | 'MEDIA' | 'HUMEDO' | 'Sin datos';

export type PestanaActiva = 'Dashboard' | 'Invernadero' | 'Graficas' | 'Control' | 'Analisis';

export interface SensorData {
  // Lectura del sensor
  valorADC: number;
  estadoSuelo: EstadoSueloTipo;
  numeroLectura: number;

  // Acciones recibidas desde el Maestro (TCP :8888)
  fertilizacionActiva: boolean;
  cicloCompleto: boolean;
  esperandoFertilizar: boolean;
  riegoActivo: boolean;

  // Navegación del robot
  sectorActual: number; // 1-3
  puntoSensado: number; // 1-3

  // Estado general del sistema
  estadoMaquina: string;

  // Metadatos
  timestamp: string;

  // Humedad calculada (0-100%)
  humedadPorcentaje: number;
}

export interface SectorInfo {
  id: 1 | 2 | 3;
  nombre: string;
  humedad: number;
  valorADC: number;
  estado: 'HUMEDO' | 'MEDIA' | 'SECO';
  ultimoRiego: string;
  puntos: [number, number, number];
}

export interface EventoRiego {
  id: string;
  sector: number;
  punto: number;
  hora: string;
  duracionSegundos: number;
  valorADC: number;
  humedad: number;
  descripcion: string;
}

export interface PuntoTendencia {
  hora: string;
  humedad: number;
  valorADC: number;
}

export interface MensajeDaniela {
  id: string;
  autor: 'daniela' | 'usuario';
  texto: string;
  hora: string;
  porVoz?: boolean;
  tarjetaSector?: {
    sector: number;
    humedad: number;
    estado: 'SECO' | 'MEDIA' | 'HUMEDO';
    ultimoRiego: string;
  };
}

/**
 * Fórmula exacta de SensorData.cs:
 * double pct = (3900.0 - ValorADC) / (3900.0 - 2800.0) * 100.0;
 * return Math.Clamp(pct, 0, 100);
 */
export function calcularHumedadPorcentaje(valorADC: number): number {
  if (valorADC <= 0) return 0;
  const pct = ((3900.0 - valorADC) / (3900.0 - 2800.0)) * 100.0;
  return Math.round(Math.max(0, Math.min(100, pct)));
}

/**
 * Clasificación según umbrales de la app y capturas móviles:
 * Óptimo >= 60% | Intermedio 35-59% | Seco < 35%
 */
export function clasificarEstadoPorHumedad(humedadPct: number): 'HUMEDO' | 'MEDIA' | 'SECO' {
  if (humedadPct >= 60) return 'HUMEDO';
  if (humedadPct >= 35) return 'MEDIA';
  return 'SECO';
}
