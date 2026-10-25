using System.IO.Ports;
using InvernaderoApp.Models;

namespace InvernaderoApp.Services
{
    public class SerialService
    {
        private SerialPort? _port;
        private SensorData  _estadoActual = new();

        // Umbrales del ESP32 (igual que en el .ino)
        private const int UMBRAL_SECO   = 3900;
        private const int UMBRAL_HUMEDO = 3200;

        public event Action<SensorData>? DatoRecibido;
        public event Action<string>?     ErrorOcurrido;
        public event Action<string>?     LineaRecibida;

        public bool Conectado => _port?.IsOpen ?? false;

        public static string[] ObtenerPuertos() => SerialPort.GetPortNames();

        // ── Conectar — 115200 baud (ESP32) ───────────────────────
        public bool Conectar(string puerto)
        {
            try
            {
                _port = new SerialPort(puerto, 115200)
                {
                    ReadTimeout  = 5000,
                    WriteTimeout = 500,
                    Encoding     = System.Text.Encoding.UTF8
                };
                _port.DataReceived += OnDataReceived;
                _port.Open();
                _estadoActual = new SensorData();
                return true;
            }
            catch (Exception ex)
            {
                ErrorOcurrido?.Invoke($"Error al conectar: {ex.Message}");
                return false;
            }
        }

        // ── Desconectar ──────────────────────────────────────────
        public void Desconectar()
        {
            if (_port?.IsOpen == true)
            {
                _port.DataReceived -= OnDataReceived;
                _port.Close();
            }
            _port?.Dispose();
            _port = null;
        }

        // ── Enviar confirmación de fertilización desde la app ────
        public void EnviarConfirmacionFertilizacion()
        {
            if (_port?.IsOpen == true)
                _port.Write("S");
        }

        // ── Recepción ────────────────────────────────────────────
        private void OnDataReceived(object sender, SerialDataReceivedEventArgs e)
        {
            try
            {
                string linea = _port!.ReadLine().Trim();
                if (string.IsNullOrEmpty(linea)) return;
                LineaRecibida?.Invoke(linea);
                ProcesarLinea(linea);
            }
            catch { }
        }

        // ── Parser ───────────────────────────────────────────────
        private void ProcesarLinea(string linea)
        {
            // ── Arranque del sistema ─────────────────────────────
            if (linea.Contains("ESCLAVO LISTO") || linea.Contains("ESCLAVO v2"))
            {
                _estadoActual = new SensorData
                {
                    EstadoMaquina = "✔ ESP32 conectado – Esperando comandos"
                };
                Emitir();
                return;
            }

            // ── ADC inicial en setup ─────────────────────────────
            // "✓ ADC OK — Lectura inicial: 3450"
            if (linea.Contains("ADC OK") && linea.Contains("Lectura inicial:"))
            {
                var idx = linea.IndexOf("Lectura inicial:");
                if (idx >= 0)
                {
                    var rawStr = linea.Substring(idx + 16).Trim();
                    if (int.TryParse(rawStr, out int adcInicial))
                    {
                        _estadoActual.ValorADC      = adcInicial;
                        _estadoActual.EstadoMaquina = $"✔ Lectura inicial: {adcInicial} ADC";
                        ActualizarEstadoDesdeADC(adcInicial);
                        Emitir();
                    }
                }
                return;
            }

            // ── Comando recibido por el ESP32 ────────────────────
            // "📩 Comando: LEER"
            if (linea.Contains("Comando:"))
            {
                if (linea.Contains("LEER"))
                    _estadoActual.EstadoMaquina = "🔍 Leyendo sensor...";
                else if (linea.Contains("FERT_ON"))
                {
                    _estadoActual.FertilizacionActiva = true;
                    _estadoActual.EstadoMaquina       = "🌱 Fertilizando el cultivo";
                }
                else if (linea.Contains("FERT_OFF"))
                {
                    _estadoActual.FertilizacionActiva = false;
                    _estadoActual.EstadoMaquina       = "✔ Fertilización completada";
                }
                Emitir();
                return;
            }

            // ── Resultado del sensor ─────────────────────────────
            // "📊 ADC: 3450  |  Estado: SECO  |  Lectura #5"
            if (linea.Contains("ADC:") && linea.Contains("Estado:") && linea.Contains("Lectura #"))
            {
                ParsearLineaSensor(linea);
                return;
            }

            // ── Respuesta al maestro ─────────────────────────────
            // "📤 Respuesta: "SECO" → OK"
            if (linea.Contains("Respuesta:"))
            {
                if      (linea.Contains("SECO"))   ActualizarEstadoSuelo("SECO");
                else if (linea.Contains("HUMEDO")) ActualizarEstadoSuelo("HUMEDO");
                else if (linea.Contains("MEDIA"))  ActualizarEstadoSuelo("MEDIA");
                Emitir();
                return;
            }
        }

        // ── Parsear la línea principal del sensor ────────────────
        // Formato: "📊 ADC: 3450  |  Estado: SECO  |  Lectura #5"
        private void ParsearLineaSensor(string linea)
        {
            try
            {
                // Limpiar emojis y caracteres especiales
                var limpia = linea
                    .Replace("📊", "")
                    .Replace("━", "")
                    .Trim();

                // Extraer ADC
                int adcVal = 0;
                var idxADC = limpia.IndexOf("ADC:");
                if (idxADC >= 0)
                {
                    var resto = limpia.Substring(idxADC + 4).Trim();
                    var fin   = resto.IndexOf('|');
                    var adcStr = fin > 0 ? resto.Substring(0, fin).Trim() : resto;
                    int.TryParse(adcStr, out adcVal);
                }

                // Extraer Estado
                string estado = "";
                var idxEst = limpia.IndexOf("Estado:");
                if (idxEst >= 0)
                {
                    var resto  = limpia.Substring(idxEst + 7).Trim();
                    var fin    = resto.IndexOf('|');
                    estado     = fin > 0 ? resto.Substring(0, fin).Trim() : resto.Trim();
                }

                // Extraer número de lectura
                int numLec = 0;
                var idxLec = limpia.IndexOf("Lectura #");
                if (idxLec >= 0)
                    int.TryParse(linea.Substring(idxLec + 9).Trim(), out numLec);

                // Actualizar estado
                _estadoActual.ValorADC      = adcVal;
                _estadoActual.NumeroLectura = numLec;
                _estadoActual.Timestamp     = DateTime.Now;

                ActualizarEstadoDesdeADC(adcVal);
                if (!string.IsNullOrEmpty(estado))
                    ActualizarEstadoSuelo(estado);

                _estadoActual.EstadoMaquina =
                    $"📊 Lectura #{numLec} — ADC: {adcVal} — {_estadoActual.EstadoSuelo}";

                Emitir();
            }
            catch { }
        }

        // ── Helpers de estado ────────────────────────────────────
        private void ActualizarEstadoDesdeADC(int adc)
        {
            if      (adc >= UMBRAL_SECO)   ActualizarEstadoSuelo("SECO");
            else if (adc <= UMBRAL_HUMEDO) ActualizarEstadoSuelo("HUMEDO");
            else                           ActualizarEstadoSuelo("MEDIA");
        }

        private void ActualizarEstadoSuelo(string estado)
        {
            _estadoActual.EstadoSuelo = estado switch
            {
                "SECO"   => "Seco",
                "HUMEDO" => "Húmedo",
                "MEDIA"  => "Intermedio",
                _        => estado
            };
            _estadoActual.RiegoActivo = estado == "SECO";
        }

        // ── Emitir / Clonar ──────────────────────────────────────
        private void Emitir() => DatoRecibido?.Invoke(CloneCiclo());

        private SensorData CloneCiclo() => new()
        {
            ValorADC             = _estadoActual.ValorADC,
            EstadoSuelo          = _estadoActual.EstadoSuelo,
            NumeroLectura        = _estadoActual.NumeroLectura,
            FertilizacionActiva  = _estadoActual.FertilizacionActiva,
            CicloCompleto        = _estadoActual.CicloCompleto,
            EsperandoFertilizar  = _estadoActual.EsperandoFertilizar,
            EstadoMaquina        = _estadoActual.EstadoMaquina,
            RiegoActivo          = _estadoActual.RiegoActivo,
            Timestamp            = _estadoActual.Timestamp,
            // Legacy
            Humedad1             = _estadoActual.ValorADC,
            Humedad2             = _estadoActual.ValorADC,
            Humedad3             = _estadoActual.ValorADC,
            SectorActual         = _estadoActual.SectorActual,
            PuntoSensado         = _estadoActual.PuntoSensado,
        };
    }
}
