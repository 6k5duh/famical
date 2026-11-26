using System;
using System.Collections.Generic;
using System.Linq;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Web.Script.Serialization;

namespace Famical.Windows
{
    public sealed class NativeUser
    {
        public long id;
        public string username, name, shift_team = "A";
        public int admin, shift_on = 1;
    }
    public sealed class NativeEvent
    {
        public long id, owner;
        public string title = "", day = "", end_day = "", clock = "", note = "";
        public string calendar_type = "solar", visibility = "shared", color = "sage", category = "family";
        public string author = "", revision = "";
        public bool annual, leap;
        public int sync_readonly;
    }
    public sealed class NativeMutation
    {
        public string key, operation, revision, creation_key, action = "save", error = "";
        public long? id;
        public NativeEvent @event, current;
        public bool conflict;
    }
    public sealed class NativeState
    {
        public NativeUser user;
        public string cookie = "", synced = "", version = "";
        public bool preferencesPending;
        public Dictionary<string, long> aliases = new Dictionary<string, long>();
        public List<NativeEvent> events = new List<NativeEvent>();
        public List<NativeMutation> pending = new List<NativeMutation>();
    }
    public sealed class SyncResponse
    {
        public int protocol;
        public bool ok, conflict, unchanged;
        public string csrf, error, version;
        public NativeUser user;
        public NativeEvent @event, current;
        public List<NativeEvent> events;
    }
    internal sealed class Almanac
    {
        public sealed class Data
        {
            public object[][] months
            {
                get; set;
            }
            public Dictionary<string, string> holidays
            {
                get; set;
            }
        }
        private readonly Data data;
        private readonly List<DateTime> starts;
        private readonly Dictionary<string, DateTime> lunarStarts = new Dictionary<string, DateTime>();
        internal static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = 16 * 1024 * 1024 };
        internal Almanac()
        {
            using (var stream = typeof(Almanac).Assembly.GetManifestResourceStream("Famical.Calendar.gz"))
            using (var gzip = new GZipStream(stream, CompressionMode.Decompress))
            using (var reader = new StreamReader(gzip))
                data = Json.Deserialize<Data>(reader.ReadToEnd());
            starts = data.months.Select(row => Parse((string)row[0])).ToList();
            for (int i = 0; i < starts.Count; i++)
            {
                var row = data.months[i];
                lunarStarts[$"{row[1]}-{row[2]}-{row[3]}"] = starts[i];
            }
        }
        internal static string Stamp(DateTime day) => day.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        internal static DateTime Parse(string value) => DateTime.ParseExact(value, "yyyy-MM-dd", CultureInfo.InvariantCulture);
        internal static string Normalize(string value)
        {
            if (value.Length == 8 && value.All(char.IsDigit))
                return value.Substring(0, 4) + "-" + value.Substring(4, 2) + "-" + value.Substring(6);
            return value;
        }
        internal string Holiday(DateTime day) => data.holidays.TryGetValue(Stamp(day), out string label) ? label : "";
        internal string Lunar(DateTime day)
        {
            int i = starts.BinarySearch(day);
            if (i < 0)
                i = ~i - 1;
            if (i < 0 || day > new DateTime(2050, 12, 31))
                return "";
            var row = data.months[i];
            return $"({row[2]}.{(day - starts[i]).Days + 1})";
        }
        internal DateTime ConvertDate(string stamp, bool lunar, bool leap)
        {
            if (!lunar)
                return Parse(stamp);
            var parts = stamp.Split('-').Select(int.Parse).ToArray();
            if (!lunarStarts.TryGetValue($"{parts[0]}-{parts[1]}-{(leap ? 1 : 0)}", out var start))
                throw new FormatException("음력 날짜·윤달을 확인해 주세요.");
            var result = start.AddDays(parts[2] - 1);
            int index = starts.BinarySearch(start);
            if (parts[2] < 1 || parts[2] > 30 || result > new DateTime(2050, 12, 31) || (index + 1 < starts.Count && result >= starts[index + 1]))
                throw new FormatException("유효하지 않은 음력 날짜입니다.");
            return result;
        }
        internal IEnumerable<Tuple<DateTime, DateTime>> Spans(NativeEvent item, int year)
        {
            int sourceYear = int.Parse(item.day.Substring(0, 4));
            int offset = int.Parse(item.end_day.Substring(0, 4)) - sourceYear;
            foreach (int y in item.annual ? new[] { year - 1, year, year + 1 } : new[] { sourceYear })
            {
                DateTime start, end;
                try
                {
                    start = ConvertDate(y.ToString("0000") + item.day.Substring(4), item.calendar_type == "lunar", item.leap);
                    end = ConvertDate((y + offset).ToString("0000") + item.end_day.Substring(4), item.calendar_type == "lunar", item.leap);
                }
                catch (Exception ex) when (ex is FormatException || ex is ArgumentOutOfRangeException) { continue; }
                if ((end - start).Days >= 0 && (end - start).Days <= 365)
                    yield return Tuple.Create(start, end);
            }
        }
        internal static bool Weekday(DateTime date) => date.DayOfWeek != DayOfWeek.Saturday && date.DayOfWeek != DayOfWeek.Sunday;
        internal static int Weekdays(DateTime start, DateTime end)
        {
            int total = 0;
            for (var day = start; day <= end; day = day.AddDays(1))
                if (Weekday(day))
                    total++;
            return total;
        }
        internal void Validate(NativeEvent item)
        {
            item.day = Normalize(item.day);
            item.end_day = Normalize(string.IsNullOrWhiteSpace(item.end_day) ? item.day : item.end_day);
            if (!System.Text.RegularExpressions.Regex.IsMatch(item.day, @"^(19|20)\d{2}-\d{2}-\d{2}$") ||
                !System.Text.RegularExpressions.Regex.IsMatch(item.end_day, @"^(19|20)\d{2}-\d{2}-\d{2}$"))
                throw new FormatException("날짜는 YYYY-MM-DD 형식으로 입력하세요.");
            var start = ConvertDate(item.day, item.calendar_type == "lunar", item.leap);
            var end = ConvertDate(item.end_day, item.calendar_type == "lunar", item.leap);
            if (start.Year < 1900 || end.Year > 2099 || (end - start).Days < 0 || (end - start).Days > 365)
                throw new FormatException("기간은 시작일부터 최대 366일입니다.");
            if (string.IsNullOrWhiteSpace(item.title) || item.title.Length > 100 || item.note.Length > 2000)
                throw new FormatException("제목은 1~100자, 메모는 2000자까지 입력하세요.");
            if (item.clock != "" && !DateTime.TryParseExact(item.clock, "HH:mm", CultureInfo.InvariantCulture, DateTimeStyles.None, out _))
                throw new FormatException("시간을 HH:mm 형식으로 입력하세요.");
        }
    }
}
