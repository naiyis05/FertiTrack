using System;

namespace InvernaderoApp.Models
{
    public class SensorData
    {
        // ── Lectura del sensor ────────────────────────────────────
        public int ValorADC { get; set; }
        public string EstadoSuelo { get; set; } = "Sin datos";  // "SECO" | "HUMEDO" | "MEDIA"
        public int NumeroLectura { get; set; }

        // ── Acciones recibidas desde el Maestro (TCP) ─────────────
        public bool FertilizacionActiva { get; set; }
        public bool CicloCompleto { get; set; }
        public bool EsperandoFertilizar { get; set; }
        public bool RiegoActivo { get; set; }

        // ── Navegación del robot ──────────────────────────────────
        public int SectorActual { get; set; }   // 1-3
        public int PuntoSensado { get; set; }   // 1-3

        // ── Estado general del sistema ────────────────────────────
        public string EstadoMaquina { get; set; } = "⏸ En espera";

        // ── Metadatos ─────────────────────────────────────────────
        public DateTime Timestamp { get; set; } = DateTime.Now;

        // ── Humedad calculada ─────────────────────────────────────
        // Calibración: 0% = ADC 3900 (seco), 100% = ADC 2800 (agua)
        public double HumedadPorcentaje
        {
            get
            {
                double pct = (3900.0 - ValorADC) / (3900.0 - 2800.0) * 100.0;
                return Math.Clamp(pct, 0, 100);
            }
        }

        // ── Legacy — compatibilidad con vistas existentes ─────────
        public float Promedio { get; set; }     // antes => ValorADC, ahora settable para flex
        public float Humedad1 { get; set; }
        public float Humedad2 { get; set; }
        public float Humedad3 { get; set; }
    }
}