export function lunarLabel(value) {
  return value ? `(${value.leap ? '윤' : '음'} ${value.month}.${value.day})` : '';
}

// One bar per visible week; overlapping bars occupy separate rows.
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
  return segments;
}
