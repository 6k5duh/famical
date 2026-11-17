import WidgetKit
import SwiftUI

struct CalendarEntry: TimelineEntry {
    let date:Date
    let snapshot:WidgetSnapshot
}
struct CalendarProvider:TimelineProvider {
    func placeholder(in context:Context) -> CalendarEntry { sample() }
    func getSnapshot(in context:Context,completion:@escaping(CalendarEntry)->Void) {
        if context.isPreview { completion(sample());return }
        Task { completion(CalendarEntry(date:Date(),snapshot:await WidgetService.refresh())) }
    }
    func getTimeline(in context:Context,completion:@escaping(Timeline<CalendarEntry>)->Void) {
        Task {
            let now=Date();let snapshot=await WidgetService.refresh(now:now)
            completion(Timeline(entries:[CalendarEntry(date:now,snapshot:snapshot)],policy:.after(now.addingTimeInterval(1800))))
        }
    }
    private func sample() -> CalendarEntry { CalendarEntry(date:Date(),snapshot:WidgetSnapshot(date:Date(),stamp:CalendarMath.stamp(Date()),events:[CalendarEvent(id:1,title:"가족 일정",day:CalendarMath.stamp(Date()),endDay:CalendarMath.stamp(Date()),clock:"18:00")],message:"")) }
}
struct CalendarWidgetView:View {
    @Environment(\.widgetFamily) private var family
    let entry:CalendarEntry
    private let green=Color(red:40/255,green:84/255,blue:73/255)
    var body:some View {
        VStack(alignment:.leading,spacing:6) {
            HStack {
                Text(entry.date,format:.dateTime.month().day().weekday()).font(.headline)
                Spacer()
                Text(CalendarMath.shift(entry.date,team:SharedStore.team,enabled:SharedStore.shiftEnabled)).font(.caption2)
            }
            if family == .systemSmall { eventList(limit:2) }
            else if family == .systemMedium {
                HStack(alignment:.top,spacing:12) { monthGrid.font(.system(size:9)).frame(maxWidth:.infinity);eventList(limit:2).frame(maxWidth:.infinity,alignment:.leading) }
            } else { monthGrid;eventList(limit:4) }
            Spacer(minLength:0)
            HStack {
                Text(entry.snapshot.message.isEmpty ? "우리 가족 달력" : entry.snapshot.message).lineLimit(1)
                Spacer()
                Text(entry.snapshot.date,style:.time)
            }.font(.system(size:9)).foregroundStyle(.secondary)
        }
        .foregroundStyle(green)
        .containerBackground(Color(red:246/255,green:245/255,blue:239/255),for:.widget)
        .widgetURL(URL(string:"famical://calendar"))
    }
    private func eventList(limit:Int) -> some View {
        VStack(alignment:.leading,spacing:5) {
            if entry.snapshot.events.isEmpty { Text(entry.snapshot.message).font(.caption).lineLimit(3) }
            ForEach(Array(entry.snapshot.events.prefix(limit)),id:\.id) { event in
                HStack(spacing:5) {
                    RoundedRectangle(cornerRadius:2).fill(barColor(event.color)).frame(width:3)
                    VStack(alignment:.leading,spacing:1) {
                        Text(event.clock.isEmpty ? "종일" : event.clock).font(.system(size:9)).foregroundStyle(.secondary)
                        Text(event.title).font(.caption).lineLimit(1).privacySensitive()
                    }
                }.fixedSize(horizontal:false,vertical:true)
            }
        }
    }
    private var monthGrid:some View {
        let cells=CalendarMath.monthCells(entry.date)
        return LazyVGrid(columns:Array(repeating:GridItem(.flexible(),spacing:2),count:7),spacing:2) {
            ForEach(Array(["일","월","화","수","목","금","토"].enumerated()),id:\.offset) { _,label in Text(label).font(.system(size:9)).foregroundStyle(.secondary) }
            ForEach(Array(cells.enumerated()),id:\.offset) { _,day in
                if let day {
                    let date=CalendarMath.calendar.date(bySetting:.day,value:day,of:entry.date) ?? entry.date
                    Text("\(day)").font(.system(size:family == .systemLarge ? 13:9,weight:day == CalendarMath.calendar.component(.day,from:entry.date) ? .bold:.regular))
                        .frame(maxWidth:.infinity,minHeight:family == .systemLarge ? 22:11)
                        .background(dayColor(date)).clipShape(RoundedRectangle(cornerRadius:3))
                } else { Text(" ").font(.system(size:9)) }
            }
        }
    }
    private func dayColor(_ date:Date) -> Color {
        let weekday=CalendarMath.calendar.component(.weekday,from:date)
        if weekday == 1 || weekday == 7 { return Color(red:243/255,green:223/255,blue:225/255) }
        let shift=CalendarMath.shift(date,team:SharedStore.team,enabled:SharedStore.shiftEnabled)
        if shift.hasSuffix("야간") { return Color(red:247/255,green:239/255,blue:187/255) }
        return shift.isEmpty ? .clear:Color(red:220/255,green:233/255,blue:216/255)
    }
    private func barColor(_ value:String) -> Color {
        switch value { case "blue":return .blue;case "rose":return .pink;case "amber":return .orange;case "violet":return .purple;default:return green }
    }
}
@main
struct FamicalWidget:Widget {
    let kind="FamicalCalendar"
    var body:some WidgetConfiguration {
        StaticConfiguration(kind:kind,provider:CalendarProvider()) { CalendarWidgetView(entry:$0) }
            .configurationDisplayName("우리 가족 달력")
            .description("월 달력, 오늘 일정과 교대조를 확인합니다.")
            .supportedFamilies([.systemSmall,.systemMedium,.systemLarge])
    }
}
