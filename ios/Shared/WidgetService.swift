import Foundation

private final class NoRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession,task: URLSessionTask,willPerformHTTPRedirection response: HTTPURLResponse,newRequest request: URLRequest,completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

private struct AccountEnvelope: Decodable {
    struct User: Decodable { let shift_team: String?; let shift_on: Int? }
    let user: User?
}

enum WidgetService {
    static func refresh(now: Date = Date()) async -> WidgetSnapshot {
        let stamp=CalendarMath.stamp(now)
        guard let server=SharedStore.server,let cookie=SharedStore.cookie else {
            return WidgetSnapshot(date:now,stamp:stamp,events:[],message:"앱에서 서버 설정·로그인해 주세요")
        }
        let includePrivate=SharedStore.includePrivate
        var parts=URLComponents(url:server.appendingPathComponent("api/events"),resolvingAgainstBaseURL:false)!
        parts.queryItems=[URLQueryItem(name:"month",value:String(stamp.prefix(7)))]
        var request=URLRequest(url:parts.url!);request.timeoutInterval=12
        request.setValue(cookie,forHTTPHeaderField:"Cookie");request.setValue("application/json",forHTTPHeaderField:"Accept")
        let config=URLSessionConfiguration.ephemeral;config.httpShouldSetCookies=false;config.httpCookieStorage=nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session=URLSession(configuration:config,delegate:NoRedirect(),delegateQueue:nil)
        defer { session.invalidateAndCancel() }
        do {
            var accountRequest=URLRequest(url:server.appendingPathComponent("api/me"));accountRequest.timeoutInterval=12
            accountRequest.setValue(cookie,forHTTPHeaderField:"Cookie")
            let (accountData,accountResponse)=try await session.data(for:accountRequest)
            if let response=accountResponse as? HTTPURLResponse,response.statusCode==200,
               let account=try? JSONDecoder().decode(AccountEnvelope.self,from:accountData),
               SharedStore.server==server,SharedStore.cookie==cookie {
                guard let user=account.user else {
                    SharedStore.snapshot=nil
                    return WidgetSnapshot(date:now,stamp:stamp,events:[],message:"앱에서 다시 로그인해 주세요")
                }
                if let team=user.shift_team,["A","B","none"].contains(team) {
                    SharedStore.defaults.set(team,forKey:"team")
                    SharedStore.defaults.set(user.shift_on != 0,forKey:"shiftEnabled")
                }
            }
            let (data,response)=try await session.data(for:request)
            guard SharedStore.server==server,SharedStore.cookie==cookie,SharedStore.includePrivate==includePrivate else {
                return WidgetSnapshot(date:now,stamp:stamp,events:[],message:"로그인 정보가 변경되었습니다. 앱을 열어 주세요")
            }
            guard let response=response as? HTTPURLResponse,response.statusCode==200 else {
                SharedStore.snapshot=nil
                return WidgetSnapshot(date:now,stamp:stamp,events:[],message:"앱에서 로그인·서버 상태를 확인해 주세요")
            }
            let all=try JSONDecoder().decode([CalendarEvent].self,from:data)
            let monthEvents=all.filter { includePrivate || $0.visibility != "private" }
            let events=Array(monthEvents.filter { $0.occurs(on:stamp) }.prefix(5))
            let snapshot=WidgetSnapshot(date:now,stamp:stamp,events:events,message:events.isEmpty ? "오늘 표시할 일정이 없습니다" : "",monthEvents:monthEvents)
            SharedStore.snapshot=snapshot;return snapshot
        } catch {
            if let old=SharedStore.snapshot,old.stamp==stamp {
                return WidgetSnapshot(date:old.date,stamp:stamp,events:old.events,message:"연결 실패 · 마지막 갱신 내용",monthEvents:old.monthEvents)
            }
            return WidgetSnapshot(date:now,stamp:stamp,events:[],message:"서버에 연결할 수 없습니다")
        }
    }
}
