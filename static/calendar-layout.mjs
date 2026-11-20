export function lunarLabel(value) {
  return value ? `(${value.leap ? '윤' : '음'} ${value.month}.${value.day})` : '';
}

// The API supplies the full solar dates of this occurrence, including lunar/annual events.
export function workProgress(event, date) {
  if (event.category !== 'work' || !event.end_day || event.day === event.end_day) return '';
  const dayNumber = stamp => Date.parse(stamp + 'T00:00:00Z') / 86400000;
  const start = dayNumber(event.day);
  return `(${dayNumber(date) - start + 1}/${dayNumber(event.end_day) - start + 1})`;
}

// Allocate lanes for whole spans first so adjacent days keep the same vertical position.
export function layoutWeek(events, dates) {
  const active = dates.filter(Boolean);
  if (!active.length) return [];
  const first = active[0], last = active.at(-1);
  const segments = events.filter(e => e.day <= last && e.end_day >= first)
    .map(event => ({event,
      startColumn: dates.indexOf(event.day < first ? first : event.day),
      endColumn: dates.indexOf(event.end_day > last ? last : event.end_day),
      continuesBefore: event.day < first, continuesAfter: event.end_day > last
    }))
    .sort((a,b) => a.event.day.localeCompare(b.event.day) || b.endColumn-a.endColumn || a.event.id-b.event.id);
  const lanes = [];
  for (const s of segments) {
    let lane = lanes.findIndex(end => end < s.startColumn);
    if (lane < 0) lane = lanes.length;
    lanes[lane] = s.endColumn;
    s.lane = lane;
  }
  return segments.flatMap(segment => {
    const {event, startColumn, endColumn} = segment;
    if (event.category !== 'work' || event.day === event.end_day || !event.end_day) return [segment];
    return dates.slice(startColumn, endColumn + 1).map((date, offset) => ({
      ...segment,
      startColumn: startColumn + offset,
      endColumn: startColumn + offset,
      continuesBefore: date > event.day,
      continuesAfter: date < event.end_day,
      progress: workProgress(event, date)
    }));
  });
}
