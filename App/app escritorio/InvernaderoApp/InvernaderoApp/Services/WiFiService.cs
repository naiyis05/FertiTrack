using System;
using System.IO;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using InvernaderoApp.Models;

namespace InvernaderoApp.Services
{
    public class WiFiService : IDisposable
    {
        // ── Conexión TCP al MAESTRO (192.168.4.1:8888) ────────────
        private TcpClient? _tcp;
        private StreamReader? _reader;
        private CancellationTokenSource _cts = new();
        private string _ip = "";
        private int _puerto = 8888;

        private SensorData _estado = new SensorData();

        // Eventos: misma firma que antes para no romper el ViewModel
        public event Action<SensorData>? DatoRecibido;
        public event Action<string>? ErrorOcurrido;

        public bool Conectado => _tcp?.Connected == true;

        // ── Conectar ──────────────────────────────────────────────
        // ip    = IP del MAESTRO, por defecto 192.168.4.1
        // puerto = 8888  (TCP server del Maestro)
        public bool Conectar(string ip = "192.168.4.1", int puerto = 8888)
        {
            try
            {
                Desconectar();          // limpia conexión previa si existe

                _ip = ip;
                _puerto = puerto;
                _cts = new CancellationTokenSource();

                _tcp = new TcpClient();
                _tcp.Connect(ip, puerto);           // conexión síncrona (breve timeout)
                _reader = new StreamReader(_tcp.GetStream());

                _estado.EstadoMaquina = "📡 Conectado al Maestro (TCP)";
                Emitir();

                // Lanza el hilo de escucha en background
                _ = Task.Run(() => BucleEscucha(_cts.Token));
                return true;
            }
            catch (Exception ex)
            {
                ErrorOcurrido?.Invoke($"Error al conectar: {ex.Message}");
                return false;
            }
        }

        // ── Desconectar ───────────────────────────────────────────
        public void Desconectar()
        {
            _cts.Cancel();
            _reader?.Dispose();
            _tcp?.Close();
            _tcp = null;
            _reader = null;
        }

        // ── Bucle de escucha (hilo background) ───────────────────
        // El Maestro envía líneas de texto cada vez que algo ocurre.
        // No hay que pedir datos; simplemente leemos hasta que se cierre.
        private async Task BucleEscucha(CancellationToken token)
        {
            try
            {
                while (!token.IsCancellationRequested && _reader != null)
                {
                    string? linea = await _reader.ReadLineAsync();
                    if (linea == null) break;   // conexión cerrada por el Maestro

                    ProcesarLinea(linea.Trim());
                }
            }
            catch (Exception ex) when (!token.IsCancellationRequested)
            {
                ErrorOcurrido?.Invoke($"Conexión perdida: {ex.Message}");
                _estado.EstadoMaquina = "🔴 Desconectado";
                Emitir();
            }
        }

        // ── Parser del protocolo del Maestro ─────────────────────
        //
        // Ejemplos de líneas que envía el Maestro:
        //   SISTEMA:CICLO_RIEGO_INICIO
        //   SECTOR:2
        //   PUNTO:1|ESTADO:SECO|ADC:3800|LECTURA:5
        //   PUNTO:2|ESTADO:HUMEDO|ADC:2400|LECTURA:6
        //   RESUMEN_SECTOR:1|P1:SECO|P2:HUMEDO|P3:SECO
        //   RIEGO:INICIADO|ULTIMA_SECA:2
        //   BOMBA:ON|POS:1234
        //   BOMBA:OFF|POS:5678
        //   RIEGO:COMPLETADO|ULTIMA_SECA:2
        //   SISTEMA:FERTILIZACION_INICIO
        //   FERTI_SECTOR:1
        //   FERTI_BOMBA:ON|SECTOR:1
        //   SISTEMA:CICLO_RIEGO_FIN
        //   SISTEMA:LISTO
        //   SISTEMA:STOP
        //
        private void ProcesarLinea(string linea)
        {
            if (string.IsNullOrEmpty(linea)) return;

            _estado.Timestamp = DateTime.Now;

            // Divide por '|' para obtener todos los pares clave:valor
            var partes = linea.Split('|');

            foreach (var parte in partes)
            {
                var kv = parte.Split(':', 2);       // máximo 2 partes (el valor puede contener ':')
                if (kv.Length < 2) continue;

                string clave = kv[0].ToUpper();
                string valor = kv[1];

                switch (clave)
                {
                    // ── Datos del sensor (vienen dentro de PUNTO:N|...) ──
                    case "ADC":
                        if (int.TryParse(valor, out int adc))
                            _estado.ValorADC = adc;
                        break;

                    case "ESTADO":
                        _estado.EstadoSuelo = valor;   // "SECO" o "HUMEDO"
                        break;

                    case "LECTURA":
                        if (int.TryParse(valor, out int lec))
                            _estado.NumeroLectura = lec;
                        break;

                    // ── Estado general del sistema ────────────────────────
                    case "SISTEMA":
                        switch (valor)
                        {
                            case "CONECTADO":
                                _estado.EstadoMaquina = "🟢 Maestro conectado";
                                break;
                            case "CICLO_RIEGO_INICIO":
                            case "CICLO_INICIADO":
                                _estado.EstadoMaquina = "🌱 Ciclo de riego iniciado";
                                _estado.CicloCompleto = false;
                                _estado.EsperandoFertilizar = false;
                                break;
                            case "CICLO_RIEGO_FIN":
                                _estado.EstadoMaquina = "🏁 Ciclo de riego completado";
                                _estado.CicloCompleto = true;
                                _estado.RiegoActivo = false;
                                break;
                            case "FERTILIZACION_INICIO":
                                _estado.EstadoMaquina = "🌿 Fertilización iniciada";
                                _estado.FertilizacionActiva = true;
                                _estado.EsperandoFertilizar = false;
                                break;
                            case "FERTILIZACION_FIN":
                                _estado.EstadoMaquina = "✅ Fertilización completada";
                                _estado.FertilizacionActiva = false;
                                break;
                            case "LISTO":
                                _estado.EstadoMaquina = "🟢 Sistema listo";
                                _estado.RiegoActivo = false;
                                _estado.FertilizacionActiva = false;
                                _estado.CicloCompleto = false;
                                break;
                            case "STOP":
                                _estado.EstadoMaquina = "🛑 Sistema detenido";
                                _estado.RiegoActivo = false;
                                _estado.FertilizacionActiva = false;
                                _estado.EsperandoFertilizar = false;
                                break;
                            default:
                                _estado.EstadoMaquina = $"ℹ️ {valor}";
                                break;
                        }
                        break;

                    case "SECTOR":
                        if (int.TryParse(valor, out int sector))
                        {
                            _estado.SectorActual = sector;
                            _estado.EstadoMaquina = $"📍 Sector {sector} / 3";
                        }
                        break;

                    case "PUNTO":
                        if (int.TryParse(valor, out int punto))
                        {
                            _estado.PuntoSensado = punto;
                            _estado.NumeroLectura = punto;
                        }
                        break;

                    case "RIEGO":
                        switch (valor)
                        {
                            case "INICIADO":
                                _estado.EstadoMaquina = "💧 Riego en curso...";
                                _estado.RiegoActivo = true;
                                break;
                            case "COMPLETADO":
                                _estado.EstadoMaquina = "✅ Riego completado";
                                _estado.RiegoActivo = false;
                                break;
                            case "OMITIDO":
                                _estado.EstadoMaquina = "✅ Sector húmedo — sin riego";
                                _estado.RiegoActivo = false;
                                break;
                            case "INTERRUMPIDO":
                                _estado.EstadoMaquina = "🛑 Riego interrumpido";
                                _estado.RiegoActivo = false;
                                break;
                            default:
                                _estado.EstadoMaquina = $"💧 {valor}";
                                break;
                        }
                        break;

                    case "BOMBA":
                        _estado.RiegoActivo = valor == "ON";
                        _estado.EstadoMaquina = valor == "ON"
                            ? "💧 Bomba de riego: ENCENDIDA"
                            : "⏹️ Bomba de riego: APAGADA";
                        break;

                    case "FERTI_SECTOR":
                        if (int.TryParse(valor, out int fSector))
                        {
                            _estado.SectorActual = fSector;
                            _estado.EstadoMaquina = $"🌿 Fertilizando sector {fSector} / 3";
                        }
                        break;

                    case "FERTI_BOMBA":
                        _estado.FertilizacionActiva = valor == "ON";
                        _estado.EstadoMaquina = valor == "ON"
                            ? "💉 Bomba fertilizante: ENCENDIDA"
                            : "⏹️ Bomba fertilizante: APAGADA";
                        break;

                    // El Maestro avisa cuando queda esperando confirmación serial de fertilizar
                    case "ESPERANDO_FERTILIZAR":
                        _estado.EsperandoFertilizar = valor == "SI";
                        if (_estado.EsperandoFertilizar)
                            _estado.EstadoMaquina = "⏳ Esperando confirmación de fertilización";
                        break;
                }
            }

            Emitir();
        }

        // ── Helpers ───────────────────────────────────────────────
        private void Emitir() => DatoRecibido?.Invoke(Clonar());

        private SensorData Clonar() => new SensorData
        {
            // Sensor
            ValorADC = _estado.ValorADC,
            EstadoSuelo = _estado.EstadoSuelo,
            NumeroLectura = _estado.NumeroLectura,
            // Acciones
            RiegoActivo = _estado.RiegoActivo,
            FertilizacionActiva = _estado.FertilizacionActiva,
            CicloCompleto = _estado.CicloCompleto,
            EsperandoFertilizar = _estado.EsperandoFertilizar,
            // Navegación
            SectorActual = _estado.SectorActual,
            PuntoSensado = _estado.PuntoSensado,
            // Estado y metadatos
            EstadoMaquina = _estado.EstadoMaquina,
            Timestamp = _estado.Timestamp
        };

        public void Dispose() => Desconectar();
    }
}