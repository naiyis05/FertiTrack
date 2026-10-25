using System;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows;
using InvernaderoApp.Models;
using InvernaderoApp.Services;
using LiveChartsCore;
using LiveChartsCore.SkiaSharpView;
using LiveChartsCore.SkiaSharpView.Painting;
using SkiaSharp;

namespace InvernaderoApp.ViewModels
{
    public class MainViewModel : INotifyPropertyChanged
    {
        // ── Servicios ─────────────────────────────────────────────
        private readonly WiFiService   _wifi    = new();   // TCP → Maestro :8888
        private readonly EsclavoService _esclavo = new();  // HTTP → Esclavo :80

        private readonly int _maxPuntos = 60;
        private bool _ultimoCicloCompleto = false;

        // ── Visualización del invernadero ─────────────────────────
        private bool _isNight = DateTime.Now.Hour < 6 || DateTime.Now.Hour >= 20;
        public bool IsNight
        {
            get => _isNight;
            set { _isNight = value; OnPropertyChanged(); }
        }

        private int _sectorActual;
        public int SectorActual
        {
            get => _sectorActual;
            set { _sectorActual = value; OnPropertyChanged(); }
        }

        // =====================================================================
        // PROPIEDADES
        // =====================================================================

        // ── Conexión Maestro ──────────────────────────────────────
        private bool _conectado;
        public bool Conectado
        {
            get => _conectado;
            set { _conectado = value; OnPropertyChanged(); OnPropertyChanged(nameof(TextoBoton)); }
        }

        public string TextoBoton => Conectado ? "Desconectar" : "Conectar";

        private string _ip = "192.168.4.1";
        public string Ip
        {
            get => _ip;
            set { _ip = value; OnPropertyChanged(); }
        }

        // ── Conexión Esclavo ──────────────────────────────────────
        private bool _esclavoConectado;
        public bool EsclavoConectado
        {
            get => _esclavoConectado;
            set { _esclavoConectado = value; OnPropertyChanged(); }
        }

        private string _ipEsclavo = "192.168.4.2";
        public string IpEsclavo
        {
            get => _ipEsclavo;
            set { _ipEsclavo = value; OnPropertyChanged(); }
        }

        // ── Sensor (fuente: Esclavo en tiempo real) ───────────────
        private double _humedad;
        public double Humedad
        {
            get => _humedad;
            set { _humedad = value; OnPropertyChanged(); }
        }

        private int _valorADC;
        public int ValorADC
        {
            get => _valorADC;
            set { _valorADC = value; OnPropertyChanged(); }
        }

        private string _estadoSuelo = "Sin datos";
        public string EstadoSuelo
        {
            get => _estadoSuelo;
            set { _estadoSuelo = value; OnPropertyChanged(); }
        }

        private int _numeroLectura;
        public int NumeroLectura
        {
            get => _numeroLectura;
            set { _numeroLectura = value; OnPropertyChanged(); }
        }

        // ── Estado general de la máquina (fuente: Maestro TCP) ───
        private string _estadoMaquina = "⏸ En espera";
        public string EstadoMaquina
        {
            get => _estadoMaquina;
            set { _estadoMaquina = value; OnPropertyChanged(); }
        }

        // ── Bombas ───────────────────────────────────────────────
        private bool _bombaRiegoActiva;
        public bool BombaRiegoActiva
        {
            get => _bombaRiegoActiva;
            set { _bombaRiegoActiva = value; OnPropertyChanged(); }
        }

        private bool _bombaFertilizanteActiva;
        public bool BombaFertilizanteActiva
        {
            get => _bombaFertilizanteActiva;
            set { _bombaFertilizanteActiva = value; OnPropertyChanged(); }
        }

        // ── Alerta fertilización ──────────────────────────────────
        private bool _mostrarAlertaFertilizacion;
        public bool MostrarAlertaFertilizacion
        {
            get => _mostrarAlertaFertilizacion;
            set { _mostrarAlertaFertilizacion = value; OnPropertyChanged(); }
        }

        // ── Mensajes ─────────────────────────────────────────────
        private string _mensajeError = "";
        public string MensajeError
        {
            get => _mensajeError;
            set { _mensajeError = value; OnPropertyChanged(); }
        }

        private string _mensajeErrorEsclavo = "";
        public string MensajeErrorEsclavo
        {
            get => _mensajeErrorEsclavo;
            set { _mensajeErrorEsclavo = value; OnPropertyChanged(); }
        }

        private int _conteoRiegos;
        public int ConteoRiegos
        {
            get => _conteoRiegos;
            set { _conteoRiegos = value; OnPropertyChanged(); }
        }

        // ── Log y gráfica ─────────────────────────────────────────
        public ObservableCollection<string> Eventos { get; } = new();
        public ObservableCollection<double> ValoresHumedad { get; } = new();
        public ISeries[] Series { get; }
        public Axis[] EjesX { get; }
        public Axis[] EjesY { get; }

        // =====================================================================
        // CONSTRUCTOR
        // =====================================================================
        public MainViewModel()
        {
            Series = new ISeries[]
            {
                new LineSeries<double>
                {
                    Values       = ValoresHumedad,
                    Name         = "Humedad %",
                    Stroke       = new SolidColorPaint(SKColors.DodgerBlue, 2),
                    Fill         = new SolidColorPaint(SKColors.DodgerBlue.WithAlpha(40)),
                    GeometrySize = 6
                }
            };

            EjesX = new Axis[] { new Axis { IsVisible = false } };
            EjesY = new Axis[] { new Axis { MinLimit = 0, MaxLimit = 100 } };

            // Maestro: estado del sistema + ciclos + bombas
            _wifi.DatoRecibido  += OnDatoMaestro;
            _wifi.ErrorOcurrido += OnErrorMaestro;

            // Esclavo: humedad en tiempo real
            _esclavo.DatoRecibido  += OnDatoEsclavo;
            _esclavo.ErrorOcurrido += OnErrorEsclavo;

            // Timer día/noche — se evalúa cada minuto según el reloj del PC
            var timerDiaNoche = new System.Windows.Threading.DispatcherTimer
            {
                Interval = TimeSpan.FromMinutes(1)
            };
            timerDiaNoche.Tick += (s, e) =>
                IsNight = DateTime.Now.Hour < 6 || DateTime.Now.Hour >= 20;
            timerDiaNoche.Start();
        }

        // =====================================================================
        // COMANDOS
        // =====================================================================

        // ── Conectar / desconectar Maestro (TCP) ─────────────────
        public void ToggleConexion()
        {
            if (!Conectado)
            {
                if (string.IsNullOrWhiteSpace(Ip))
                {
                    MensajeError = "Ingresa la IP del Maestro (ej: 192.168.4.1)";
                    return;
                }

                bool ok = _wifi.Conectar(Ip, 8888);
                if (ok)
                {
                    Conectado    = true;
                    MensajeError = "";
                    AgregarEvento($"📡 Maestro conectado {Ip}:8888");
                }
                else
                {
                    MensajeError = $"No se pudo conectar al Maestro {Ip}:8888";
                }
            }
            else
            {
                _wifi.Desconectar();
                Conectado     = false;
                EstadoMaquina = "⏸ Desconectado";
                AgregarEvento("🔌 Maestro desconectado");
            }
        }

        // ── Conectar / desconectar Esclavo (HTTP polling) ─────────
        public void ToggleEsclavo()
        {
            if (!EsclavoConectado)
            {
                if (string.IsNullOrWhiteSpace(IpEsclavo))
                {
                    MensajeErrorEsclavo = "Ingresa la IP del Esclavo (ej: 192.168.4.2)";
                    return;
                }

                _esclavo.Iniciar(IpEsclavo, intervaloSegundos: 3);
                EsclavoConectado    = true;
                MensajeErrorEsclavo = "";
                AgregarEvento($"🌱 Esclavo conectado {IpEsclavo} — polling cada 3 s");
            }
            else
            {
                _esclavo.Detener();
                EsclavoConectado = false;
                AgregarEvento("🔌 Esclavo desconectado");
            }
        }

        // ── Fertilización ─────────────────────────────────────────
        public void ConfirmarFertilizacion()
        {
            MostrarAlertaFertilizacion = false;
            AgregarEvento("🌱 Fertilización confirmada por usuario.");
        }

        public void IgnorarFertilizacion()
        {
            MostrarAlertaFertilizacion = false;
            AgregarEvento("🚫 Alerta de fertilización descartada.");
        }

        // =====================================================================
        // RECEPCIÓN DE DATOS
        // =====================================================================

        // ── Datos del MAESTRO — estado del sistema, bombas, ciclos ──
        private void OnDatoMaestro(SensorData dato)
        {
            Application.Current.Dispatcher.Invoke(() =>
            {
                // El Maestro nos dice qué hace el robot, no el sensor
                EstadoMaquina           = dato.EstadoMaquina;
                BombaRiegoActiva        = dato.RiegoActivo;
                BombaFertilizanteActiva = dato.FertilizacionActiva;
                SectorActual            = dato.SectorActual;
                MensajeError            = "";

                // Conteo de ciclos
                if (dato.CicloCompleto && !_ultimoCicloCompleto)
                    ConteoRiegos++;
                _ultimoCicloCompleto = dato.CicloCompleto;

                // Alerta de fertilización
                if (dato.EsperandoFertilizar && !MostrarAlertaFertilizacion)
                {
                    MostrarAlertaFertilizacion = true;
                    AgregarEvento("⚠️ Ciclo completado — ¿Desea fertilizar?");
                }
                else if (!dato.EsperandoFertilizar)
                {
                    MostrarAlertaFertilizacion = false;
                }

                // Si el Maestro también trae datos de sensor (cuando él sensa),
                // los usamos solo si el EsclavoService no está activo
                if (!EsclavoConectado && dato.ValorADC > 0)
                {
                    ValorADC      = dato.ValorADC;
                    Humedad       = dato.HumedadPorcentaje;
                    EstadoSuelo   = dato.EstadoSuelo;
                    NumeroLectura = dato.NumeroLectura;
                    ActualizarGrafica();
                }

                RegistrarEventoSiRelevante(dato.EstadoMaquina);
            });
        }

        // ── Datos del ESCLAVO — humedad en tiempo real ─────────────
        private void OnDatoEsclavo(SensorData dato)
        {
            Application.Current.Dispatcher.Invoke(() =>
            {
                // El Esclavo es la fuente de verdad del sensor
                ValorADC      = dato.ValorADC;
                Humedad       = dato.HumedadPorcentaje;
                EstadoSuelo   = dato.EstadoSuelo;
                NumeroLectura = dato.NumeroLectura;
                MensajeErrorEsclavo = "";

                ActualizarGrafica();

                // Log solo cuando el estado del suelo cambia
                RegistrarEstadoSueloSiCambia(dato.EstadoSuelo);
            });
        }

        private void ActualizarGrafica()
        {
            ValoresHumedad.Add(Humedad);
            if (ValoresHumedad.Count > _maxPuntos)
                ValoresHumedad.RemoveAt(0);
        }

        // ── Errores ───────────────────────────────────────────────
        private void OnErrorMaestro(string error)
        {
            Application.Current.Dispatcher.Invoke(() =>
            {
                MensajeError  = error;
                Conectado     = false;
                EstadoMaquina = "🔴 Maestro sin conexión";
                AgregarEvento($"❌ Maestro: {error}");
            });
        }

        private void OnErrorEsclavo(string error)
        {
            Application.Current.Dispatcher.Invoke(() =>
            {
                MensajeErrorEsclavo = error;
                // No desconectamos — el esclavo puede recuperarse solo en el próximo tick
                AgregarEvento($"⚠️ Esclavo: {error}");
            });
        }

        // =====================================================================
        // HELPERS
        // =====================================================================
        private string _ultimoEstadoLogueado = "";
        private void RegistrarEventoSiRelevante(string estado)
        {
            if (estado == _ultimoEstadoLogueado) return;
            _ultimoEstadoLogueado = estado;

            bool esRelevante =
                estado.Contains("Sector")     ||
                estado.Contains("riego")      ||
                estado.Contains("Bomba")      ||
                estado.Contains("Fertiliz")   ||
                estado.Contains("completado") ||
                estado.Contains("Detenido")   ||
                estado.Contains("Error");

            if (esRelevante) AgregarEvento(estado);
        }

        private string _ultimoSuelo = "";
        private void RegistrarEstadoSueloSiCambia(string estado)
        {
            if (estado == _ultimoSuelo) return;
            _ultimoSuelo = estado;
            AgregarEvento($"🌱 Suelo → {estado}  ADC:{ValorADC}  Hum:{Humedad:F0}%");
        }

        private void AgregarEvento(string mensaje)
        {
            var hora = DateTime.Now.ToString("HH:mm:ss");
            Eventos.Insert(0, $"[{hora}] {mensaje}");
            if (Eventos.Count > 30) Eventos.RemoveAt(Eventos.Count - 1);
        }

        // =====================================================================
        // INotifyPropertyChanged
        // =====================================================================
        public event PropertyChangedEventHandler? PropertyChanged;
        protected void OnPropertyChanged([CallerMemberName] string? name = null)
            => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}
