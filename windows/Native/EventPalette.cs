using System;
using System.Linq;
using System.Windows.Media;

namespace Famical.Windows
{
    internal static class EventPalette
    {
        internal static readonly string[] Ids = {"sage","blue","rose","amber","violet","red","coral","orange","gold","lime","teal","cyan","indigo","magenta","brown","slate"};
        internal static readonly string[] Labels = {"숲초록","파랑","진분홍","황토","보라","빨강","산호","주황","금빛","올리브","청록","하늘","남보라","자홍","갈색","회색"};
        private static readonly string[] Values = {"#36705b","#3568a1","#a74d6d","#9d5c1d","#7454a1","#b52e32","#b9473a","#b84b00","#876500","#4f7200","#007a70","#007594","#5146b8","#ac287a","#84532b","#52616b"};
        private static readonly SolidColorBrush[] Brushes = Values.Select(value => {
            var brush = (SolidColorBrush)new BrushConverter().ConvertFromString(value);
            brush.Freeze();
            return brush;
        }).ToArray();
        internal static SolidColorBrush Color(string id) => Brushes[Math.Max(0, Array.IndexOf(Ids, id))];
    }
}
