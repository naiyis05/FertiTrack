using System;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using InvernaderoApp.Models;

namespace InvernaderoApp.Services
{
    /// <summary>
    /// Consulta al Esclavo (192.168.4.2) por HTTP cada N segundos
    /// y emite los datos igual que WiFiService, para que el ViewModel
    /// los reciba por el mismo evento DatoRecibido.
    /// </summary>
    public class EsclavoService : IDisposable
    {
        private readonly HttpClient _http = new HttpClient { Timeout = TimeSpan.FromSeconds(3) };
        private CancellationTokenSource _cts = new();
        private string _urlBase = "";

        public event Action<SensorData>? DatoRecibido;
        public event Action<string>?     ErrorOcurrido;

        public bool Activo => !string.IsNullOrEmpty(_urlBase);

        // ── Iniciar polling ───────────────────────────────────────
        // ip       = IP del esclavo, por defecto 192.168.4.2
        // intervalo = segundos entre consultas, por defecto 3
        public void Iniciar(string ip = "192.168.4.2", int intervaloSegundos = 3)
        {
            Detener();
            _urlBase = ip.StartsWith("http") ? ip : $"http://{ip}";
            _cts     = new CancellationTokenSource();
            _ = Task.Run(() => BuclePolling(intervaloSegundos, _cts.Token));
        }

        public void Detener()
        {
            _cts.Cancel();
            _urlBase = "";
        }

        // ── Bucle de polling ──────────────────────────────────────
        private async Task BuclePolling(int intervalo, CancellationToken token)
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    string resp = await _http.GetStringAsync($"{_urlBase}/leer", token);
                    resp = resp.Trim();
                    if (!string.IsNullOrEmpty(resp))
                        DatoRecibido?.Invoke(Parsear(resp));
                }
                catch (OperationCanceledException) { break; }
                catch (Exception ex)
                {
                    ErrorOcurrido?.Invoke($"Esclavo sin respuesta: {ex.Message}");
                }

                try { await Task.Delay(TimeSpan.FromSeconds(intervalo), token); }
                catch (OperationCanceledException) { break; }
            }
        }

        // ── Parser — mismo formato que usa el Maestro ─────────────
        // Respuesta del esclavo: "ADC:XXXX|ESTADO:SECO|LECTURA:N"
        private SensorData Parsear(string linea)
        {
            var dato = new SensorData { Timestamp = DateTime.Now };

            foreach (var parte in linea.Split('|'))
            {
                var kv = parte.Split(':', 2);
                if (kv.Length < 2) continue;

                switch (kv[0].ToUpper())
                {
                    case "ADC":
                        if (int.TryParse(kv[1], out int adc))
                            dato.ValorADC = adc;
                        break;
                    case "ESTADO":
                        // El esclavo envía "SECO" o "HUMEDO"
                        // pero también puede devolver "MEDIA" si se añade
                        dato.EstadoSuelo = kv[1];
                        break;
                    case "LECTURA":
                        if (int.TryParse(kv[1], out int lec))
                            dato.NumeroLectura = lec;
                        break;
                }
            }

            // EstadoMaquina descriptivo para distinguirlo del dato del Maestro
            dato.EstadoMaquina = $"📡 Esclavo — {dato.EstadoSuelo}  ADC:{dato.ValorADC}";
            return dato;
        }

        // ── Comando fertilización (opcional, por si se usa desde la app) ──
        public async Task EnviarFertilizacion(bool encender)
        {
            if (!Activo) return;
            string ruta = encender ? "/fert_on" : "/fert_off";
            try   { await _http.GetStringAsync($"{_urlBase}{ruta}"); }
            catch (Exception ex) { ErrorOcurrido?.Invoke($"Error fert: {ex.Message}"); }
        }

        public void Dispose() => Detener();
    }
}
