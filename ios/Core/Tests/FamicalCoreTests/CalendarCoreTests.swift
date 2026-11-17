import XCTest
@testable import FamicalCore
final class CalendarCoreTests: XCTestCase {
    func testServerAddress() {
        XCTAssertEqual(ServerAddress.normalize(" Calendar.Example.com ")?.absoluteString,"https://calendar.example.com/")
        for value in ["http://calendar.example.com","https://u:p@calendar.example.com","https://calendar.example.com/path","https://calendar.example.com/?secret=x","file:///private","https://calendar.example.com:0"] { XCTAssertNil(ServerAddress.normalize(value),value) }
        let base=URL(string:"https://calendar.example.com/")!
        XCTAssertTrue(ServerAddress.sameOrigin(base,URL(string:"https://calendar.example.com:443/api/me")!))
        XCTAssertFalse(ServerAddress.sameOrigin(base,URL(string:"https://calendar.example.com.evil.test/")!))
    }
    func testShiftsAndCalendar() {
        var c=Calendar(identifier:.gregorian);c.timeZone=TimeZone(secondsFromGMT:0)!
        func date(_ day: Int) -> Date { c.date(from:DateComponents(year:2026,month:11,day:day))! }
        XCTAssertEqual(CalendarMath.shift(date(17),team:"A",enabled:true,calendar:c),"A조 야간")
        XCTAssertEqual(CalendarMath.shift(date(23),team:"B",enabled:true,calendar:c),"B조 야간")
        XCTAssertEqual(CalendarMath.shift(date(21),team:"A",enabled:true,calendar:c),"")
        XCTAssertEqual(CalendarMath.shift(date(17),team:"A",enabled:false,calendar:c),"")
        XCTAssertEqual(CalendarMath.monthCells(date(17),calendar:c).compactMap{$0},Array(1...30))
    }
    func testLegacyEventDecoding() throws {
        let data=Data(#"{"id":1,"title":"일정","day":"2026-11-17","clock":""}"#.utf8)
        let e=try JSONDecoder().decode(CalendarEvent.self,from:data)
        XCTAssertEqual(e.endDay,e.day);XCTAssertEqual(e.visibility,"shared")
    }
    func testInclusiveRange() {
        let e=CalendarEvent(id:1,title:"여행",day:"2026-11-17",endDay:"2026-11-20",clock:"")
        XCTAssertTrue(e.occurs(on:"2026-11-17"));XCTAssertTrue(e.occurs(on:"2026-11-20"));XCTAssertFalse(e.occurs(on:"2026-11-21"))
    }
}
