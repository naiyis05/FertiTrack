using System;
using System.Globalization;
using System.Windows;
using System.Windows.Data;

namespace InvernaderoApp.Helpers
{
    /// <summary>
    /// Convierte un valor de humedad (0-100) a un ancho en píxeles.
    /// ConverterParameter = ancho máximo del contenedor (ej: "160").
    /// </summary>
    public class HumedadAnchoConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            double humedad = value is double d ? d : 0;
            double maxAncho = parameter is string s && double.TryParse(s, out double p) ? p : 100;
            return Math.Max(0, humedad / 100.0 * maxAncho);
        }

        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
            => DependencyProperty.UnsetValue;
    }
}