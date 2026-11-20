"""Build compact, offline Korean calendar data shared by the native clients."""
import json
import gzip
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from korean_lunar_calendar import KoreanLunarCalendar
from calendar_holidays import holidays_for_year

months = []
moon = KoreanLunarCalendar()
for year in range(1899, 2051):
    for month in range(1, 13):
        for leap in (False, True):
            if moon.setLunarDate(year, month, 1, leap) and bool(moon.isIntercalation) == leap:
                stamp = moon.SolarIsoFormat()
                if '1900-01-01' <= stamp <= '2050-12-31':
                    months.append([stamp, year, month, int(leap)])
months.sort()
payload = {'months': months, 'holidays': {
    stamp: reason for year in range(1900, 2100) for stamp, reason in holidays_for_year(year).items()}}
raw = json.dumps(payload, ensure_ascii=False, separators=(',', ':')).encode()
target = Path(__file__).with_name('assets') / 'calendar.dat'
target.parent.mkdir(exist_ok=True)
target.write_bytes(gzip.compress(raw, mtime=0))
print(len(raw), 'bytes JSON;', target.stat().st_size, 'bytes gzip')
