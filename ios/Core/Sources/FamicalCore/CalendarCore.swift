import Foundation

public enum ServerAddress {
    public static func normalize(_ input: String) -> URL? {
        var text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if !text.contains("://") { text = "https://" + text }
        guard var parts = URLComponents(string: text), parts.scheme?.lowercased() == "https",
              let host = parts.host, !host.isEmpty, parts.user == nil, parts.password == nil,
              parts.query == nil, parts.fragment == nil, parts.path.isEmpty || parts.path == "/",
              parts.port == nil || (1...65535).contains(parts.port!) else { return nil }
        parts.scheme = "https"; parts.host = host.lowercased(); parts.path = "/"
        if parts.port == 443 { parts.port = nil }
        return parts.url
    }
    public static func sameOrigin(_ a: URL, _ b: URL) -> Bool {
        b.scheme?.lowercased() == "https" && b.user == nil && b.password == nil &&
        a.host?.lowercased() == b.host?.lowercased() && (a.port ?? 443) == (b.port ?? 443)
    }
}

public struct CalendarEvent: Codable, Equatable {
    public let id: Int
    public let title: String
    public let day: String
    public let endDay: String
    public let clock: String
    public let visibility: String
    public let color: String
    enum CodingKeys: String, CodingKey { case id, title, day, endDay = "end_day", clock, visibility, color }
    public init(from decoder: Decoder) throws {
        let c=try decoder.container(keyedBy:CodingKeys.self)
        id=try c.decode(Int.self,forKey:.id);title=try c.decode(String.self,forKey:.title)
        day=try c.decode(String.self,forKey:.day);endDay=try c.decodeIfPresent(String.self,forKey:.endDay) ?? day
        clock=try c.decodeIfPresent(String.self,forKey:.clock) ?? ""
        visibility=try c.decodeIfPresent(String.self,forKey:.visibility) ?? "shared"
        color=try c.decodeIfPresent(String.self,forKey:.color) ?? "sage"
    }
    public init(id: Int, title: String, day: String, endDay: String, clock: String, visibility: String = "shared", color: String = "sage") {
        self.id=id; self.title=title; self.day=day; self.endDay=endDay; self.clock=clock; self.visibility=visibility; self.color=color
    }
    public func occurs(on stamp: String) -> Bool { day <= stamp && endDay >= stamp }
}

public enum CalendarMath {
    public static var calendar: Calendar { Calendar(identifier: .gregorian) }
    public static func stamp(_ date: Date, calendar: Calendar = Calendar(identifier: .gregorian)) -> String {
        let c=calendar.dateComponents([.year,.month,.day], from:date)
        return String(format:"%04d-%02d-%02d",c.year!,c.month!,c.day!)
    }
    public static func shift(_ date: Date, team: String, enabled: Bool, calendar: Calendar = Calendar(identifier: .gregorian)) -> String {
        let weekday=calendar.component(.weekday,from:date)
        guard enabled, weekday != 1, weekday != 7,
              let anchor=calendar.date(from:DateComponents(year:2026,month:11,day:16)),
              let days=calendar.dateComponents([.day],from:calendar.startOfDay(for:anchor),to:calendar.startOfDay(for:date)).day else { return "" }
        let week=Int(floor(Double(days)/7.0)); let aNight=((week % 2)+2)%2 == 0
        return "\(team)조 \((team == "B" ? !aNight : aNight) ? "야간" : "주간")"
    }
    public static func monthCells(_ date: Date, calendar: Calendar = Calendar(identifier: .gregorian)) -> [Int?] {
        guard let interval=calendar.dateInterval(of:.month,for:date), let range=calendar.range(of:.day,in:.month,for:date) else { return [] }
        let offset=calendar.component(.weekday,from:interval.start)-1
        var cells: [Int?] = Array(repeating:nil,count:offset) + range.map { Optional($0) }
        while cells.count % 7 != 0 { cells.append(nil) }; return cells
    }
}
