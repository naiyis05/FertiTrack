using InvernaderoApp.ViewModels;
using System;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Media;
using System.Windows.Threading;

namespace InvernaderoApp
{
    

    public partial class MainWindow : Window
    {
        private readonly MainViewModel _vm;
        private readonly DispatcherTimer _timerReloj = new() { Interval = TimeSpan.FromSeconds(30) };

        public MainWindow()
        {
            InitializeComponent();
            _vm = new MainViewModel();
            DataContext = _vm;

            _vm.PropertyChanged += OnViewModelChanged;

            // Reloj del panel invernadero
            _timerReloj.Tick += (s, e) => ActualizarReloj();
            _timerReloj.Start();
            ActualizarReloj();

            MostrarPanel("Dashboard");
            SetNavActivo(BtnDashboard);
        }

        // ══════════════════════════════════════════
        // NAVEGACIÓN — incluye el nuevo BtnInvernadero
        // ══════════════════════════════════════════
        private void BtnDashboard_Click(object sender, RoutedEventArgs e)   => Navegar("Dashboard",    BtnDashboard);
        private void BtnInvernadero_Click(object sender, RoutedEventArgs e) => Navegar("Invernadero",  BtnInvernadero);
        private void BtnGraficas_Click(object sender, RoutedEventArgs e)
        {
            Navegar("Graficas", BtnGraficas);
            ListaEventosGrafica.ItemsSource = _vm.Eventos;
        }
        private void BtnControl_Click(object sender, RoutedEventArgs e)  => Navegar("Control",  BtnControl);
        private void BtnAnalisis_Click(object sender, RoutedEventArgs e) => Navegar("Analisis", BtnAnalisis);

        private void Navegar(string panel, Button boton) { MostrarPanel(panel); SetNavActivo(boton); }

        private void MostrarPanel(string nombre)
        {
            PanelDashboard.Visibility    = nombre == "Dashboard"   ? Visibility.Visible : Visibility.Collapsed;
            PanelInvernadero.Visibility  = nombre == "Invernadero" ? Visibility.Visible : Visibility.Collapsed;
            PanelGraficas.Visibility     = nombre == "Graficas"    ? Visibility.Visible : Visibility.Collapsed;
            PanelControl.Visibility      = nombre == "Control"     ? Visibility.Visible : Visibility.Collapsed;
            PanelAnalisis.Visibility     = nombre == "Analisis"    ? Visibility.Visible : Visibility.Collapsed;
        }

        private void SetNavActivo(Button activo)
        {
            foreach (var btn in new[] { BtnDashboard, BtnInvernadero, BtnGraficas, BtnControl, BtnAnalisis })
                btn.Style = (Style)FindResource(btn == activo ? "NavButtonActivo" : "NavButtonStyle");
        }

        // ══════════════════════════════════════════
        // RELOJ del panel invernadero
        // ══════════════════════════════════════════
        private void ActualizarReloj()
        {
            TxtHoraInvernadero.Text = DateTime.Now.ToString("HH:mm  |  ddd dd MMM");
        }

        // ══════════════════════════════════════════
        // CONEXIÓN MAESTRO (TCP)
        // ══════════════════════════════════════════
        private void BtnConectar_Click(object sender, RoutedEventArgs e)
        {
            _vm.Ip = TxtIP.Text.Trim();
            _vm.ToggleConexion();

            BtnConectar.Content    = _vm.Conectado ? "Desconectar" : "Conectar";
            BtnConectar.Background = _vm.Conectado
                ? new SolidColorBrush(Color.FromRgb(239, 68, 68))
                : new SolidColorBrush(Color.FromRgb(34, 197, 94));
            TxtError.Text = _vm.MensajeError;
            ActualizarIndicadorMaestro();
        }

        // ══════════════════════════════════════════
        // CONEXIÓN ESCLAVO (HTTP)
        // ══════════════════════════════════════════
        private void BtnConectarEsclavo_Click(object sender, RoutedEventArgs e)
        {
            _vm.IpEsclavo = TxtIPEsclavo.Text.Trim();
            _vm.ToggleEsclavo();

            BtnConectarEsclavo.Content    = _vm.EsclavoConectado ? "Detener esclavo" : "Conectar esclavo";
            BtnConectarEsclavo.Background = _vm.EsclavoConectado
                ? new SolidColorBrush(Color.FromRgb(239, 68, 68))
                : new SolidColorBrush(Color.FromRgb(14, 165, 233));
            TxtErrorEsclavo.Text = _vm.MensajeErrorEsclavo;
            ActualizarIndicadorEsclavo();
        }

        // ══════════════════════════════════════════
        // POPUP FERTILIZACIÓN
        // ══════════════════════════════════════════
        private void BtnSiFertilizar_Click(object sender, RoutedEventArgs e)
        {
            _vm.ConfirmarFertilizacion();
            TarjetaFertilizacion.Visibility = Visibility.Collapsed;
        }

        private void BtnNoFertilizar_Click(object sender, RoutedEventArgs e)
        {
            _vm.IgnorarFertilizacion();
            TarjetaFertilizacion.Visibility = Visibility.Collapsed;
        }

        // ══════════════════════════════════════════
        // REACTIVIDAD
        // ══════════════════════════════════════════
        private void OnViewModelChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs e)
        {
            Dispatcher.Invoke(() =>
            {
                switch (e.PropertyName)
                {
                    case nameof(_vm.Humedad):
                        ActualizarHumedad();
                        break;
                    case nameof(_vm.EstadoSuelo):
                        ActualizarEstadoSuelo();
                        break;
                    case nameof(_vm.ValorADC):
                    case nameof(_vm.NumeroLectura):
                        ActualizarDatosEsclavo();
                        break;
                    case nameof(_vm.BombaRiegoActiva):
                        ActualizarRiego();
                        break;
                    case nameof(_vm.EstadoMaquina):
                        ActualizarEstadoMaquina();
                        break;
                    case nameof(_vm.ConteoRiegos):
                        TxtKpiRiegos.Text = _vm.ConteoRiegos.ToString();
                        break;
                    case nameof(_vm.MostrarAlertaFertilizacion):
                        TarjetaFertilizacion.Visibility = _vm.MostrarAlertaFertilizacion
                            ? Visibility.Visible : Visibility.Collapsed;
                        break;
                    case nameof(_vm.Conectado):
                        ActualizarIndicadorMaestro();
                        break;
                    case nameof(_vm.EsclavoConectado):
                        ActualizarIndicadorEsclavo();
                        BtnConectarEsclavo.Content    = _vm.EsclavoConectado ? "Detener esclavo" : "Conectar esclavo";
                        BtnConectarEsclavo.Background = _vm.EsclavoConectado
                            ? new SolidColorBrush(Color.FromRgb(239, 68, 68))
                            : new SolidColorBrush(Color.FromRgb(14, 165, 233));
                        break;
                    case nameof(_vm.MensajeErrorEsclavo):
                        TxtErrorEsclavo.Text = _vm.MensajeErrorEsclavo;
                        break;
                }
                ListaEventos.ItemsSource = _vm.Eventos;
            });
        }

        // ══════════════════════════════════════════
        // HELPERS DE UI
        // ══════════════════════════════════════════
        private void ActualizarHumedad()
        {
            TxtHumedadNum.Text = _vm.Humedad.ToString("F0");
            TxtKpiHumedad.Text = _vm.Humedad.ToString("F0") + "%";
            TarjetaHumedad.Background = _vm.Humedad switch
            {
                >= 60 => new SolidColorBrush(Color.FromRgb(240, 253, 244)),
                >= 35 => new SolidColorBrush(Color.FromRgb(255, 251, 235)),
                _     => new SolidColorBrush(Color.FromRgb(254, 242, 242))
            };
        }

        private void ActualizarEstadoSuelo()
        {
            TxtEstadoSuelo.Text = _vm.EstadoSuelo;
            TxtKpiEstado.Text   = _vm.EstadoSuelo;

            var gris = new SolidColorBrush(Color.FromRgb(209, 213, 219));
            PuntoHumedo.Fill = PuntoIntermedio.Fill = PuntoSeco.Fill = gris;

            string estado = _vm.EstadoSuelo.ToUpper();
            if      (estado.Contains("HUMEDO") || estado.Contains("HÚMEDO"))
                PuntoHumedo.Fill     = new SolidColorBrush(Color.FromRgb(34,  197, 94));
            else if (estado.Contains("SECO"))
                PuntoSeco.Fill       = new SolidColorBrush(Color.FromRgb(239, 68,  68));
            else
                PuntoIntermedio.Fill = new SolidColorBrush(Color.FromRgb(234, 179, 8));
        }

        private void ActualizarDatosEsclavo()
        {
            TxtADCEsclavo.Text     = _vm.ValorADC.ToString();
            TxtLecturaEsclavo.Text = _vm.NumeroLectura.ToString();
        }

        private void ActualizarRiego()
        {
            bool activo = _vm.BombaRiegoActiva;
            TxtRiego.Text       = activo ? "Activo"   : "Inactivo";
            TxtRiego.Foreground = new SolidColorBrush(
                activo ? Color.FromRgb(37, 99, 235) : Color.FromRgb(107, 114, 128));
            TxtRiegoCtrl.Text       = activo ? "ON"  : "OFF";
            TxtEstadoRiegoCtrl.Text = "Estado: " + (activo ? "Regando" : "Inactivo");
        }

        private void ActualizarEstadoMaquina()
        {
            TxtEstadoMaquina.Text = _vm.EstadoMaquina;
            PuntoMaquina.Fill = _vm.EstadoMaquina switch
            {
                var s when s.Contains("Riego")  || s.Contains("💧") => new SolidColorBrush(Color.FromRgb(37,  99,  235)),
                var s when s.Contains("Ferti")  || s.Contains("🌱") => new SolidColorBrush(Color.FromRgb(34,  197, 94)),
                var s when s.Contains("Deteni") || s.Contains("🛑") => new SolidColorBrush(Color.FromRgb(239, 68,  68)),
                _ => new SolidColorBrush(Color.FromRgb(156, 163, 175))
            };
        }

        private void ActualizarIndicadorMaestro()
        {
            PuntoConexion.Fill = new SolidColorBrush(_vm.Conectado
                ? Color.FromRgb(34, 197, 94) : Color.FromRgb(209, 213, 219));
            TextoConexion.Text = _vm.Conectado ? _vm.Ip : "Sin conexión";
        }

        private void ActualizarIndicadorEsclavo()
        {
            var azul = Color.FromRgb(14, 165, 233);
            var gris = Color.FromRgb(209, 213, 219);

            PuntoEsclavo.Fill         = new SolidColorBrush(_vm.EsclavoConectado ? azul : gris);
            TextoEsclavo.Text         = _vm.EsclavoConectado ? _vm.IpEsclavo : "Sin conexión";
            PuntoEsclavoDash.Fill     = new SolidColorBrush(_vm.EsclavoConectado ? azul : gris);
            TxtEstadoEsclavoDash.Text = _vm.EsclavoConectado ? "ON" : "OFF";
            TxtEstadoEsclavoDash.Foreground = new SolidColorBrush(
                _vm.EsclavoConectado ? azul : Color.FromRgb(156, 163, 175));
        }
    }
}
