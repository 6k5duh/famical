import SwiftUI
import UIKit
import WebKit
import WidgetKit

struct CalendarBrowser: UIViewRepresentable {
    let server: URL
    let reload: Int
    let sync: Int
    func makeCoordinator() -> Coordinator { Coordinator(server:server) }
    func makeUIView(context:Context) -> WKWebView {
        let config=WKWebViewConfiguration();config.websiteDataStore = .default()
        let view=WKWebView(frame:.zero,configuration:config)
        view.navigationDelegate=context.coordinator;view.uiDelegate=context.coordinator
        view.allowsBackForwardNavigationGestures=true
        view.isInspectable=false
        context.coordinator.view=view
        config.websiteDataStore.httpCookieStore.add(context.coordinator)
        view.load(URLRequest(url:server));return view
    }
    func updateUIView(_ view:WKWebView,context:Context) {
        if context.coordinator.reload != reload { context.coordinator.reload=reload;view.load(URLRequest(url:server)) }
        if context.coordinator.sync != sync { context.coordinator.sync=sync;context.coordinator.syncCookies() }
    }
    static func dismantleUIView(_ view:WKWebView,coordinator:Coordinator) {
        view.configuration.websiteDataStore.httpCookieStore.remove(coordinator)
        view.stopLoading();view.navigationDelegate=nil;view.uiDelegate=nil
    }
    final class Coordinator: NSObject,WKNavigationDelegate,WKUIDelegate,WKHTTPCookieStoreObserver {
        let server:URL
        weak var view:WKWebView?
        var reload = -1
        var sync = -1
        init(server:URL) { self.server=server }
        func cookiesDidChange(in cookieStore:WKHTTPCookieStore) { syncCookies() }
        func webView(_ webView:WKWebView,didFinish navigation:WKNavigation!) { syncCookies() }
        func syncCookies() {
            guard let view else { return }
            view.configuration.websiteDataStore.httpCookieStore.getAllCookies { [weak self] cookies in
                guard let self,SharedStore.server==self.server else{return}
                let cookie=cookies.first {
                    $0.name == "session" && $0.domain.trimmingCharacters(in:CharacterSet(charactersIn:".")).lowercased() == self.server.host?.lowercased() && $0.isSecure && ($0.expiresDate == nil || $0.expiresDate! > Date())
                }
                let saved=SharedStore.setCookie(cookie.map { "\($0.name)=\($0.value)" })
                if !saved { self.notice("위젯 로그인 공유 권한이 없습니다. Xcode의 App Groups와 Keychain Sharing 서명 설정을 확인해 주세요.") }
                WidgetCenter.shared.reloadAllTimelines()
            }
            view.evaluateJavaScript("localStorage.getItem('familyCalendar.shift')") { value,_ in
                guard let text=value as? String,let data=text.data(using:.utf8),
                      let values=(try? JSONSerialization.jsonObject(with:data)) as? [String:Any] else{return}
                let team=values["team"] as? String ?? "A"
                SharedStore.defaults.set(["A","B","none"].contains(team) ? team:"A",forKey:"team")
                SharedStore.defaults.set(values["on"] as? Bool ?? true,forKey:"shiftEnabled")
                WidgetCenter.shared.reloadAllTimelines()
            }
        }
        func webView(_ webView:WKWebView,decidePolicyFor action:WKNavigationAction,decisionHandler:@escaping(WKNavigationActionPolicy)->Void) {
            guard let url=action.request.url else{decisionHandler(.cancel);return}
            if ServerAddress.sameOrigin(server,url) { decisionHandler(.allow);return }
            decisionHandler(.cancel)
            if action.navigationType == .linkActivated,url.scheme == "https" { UIApplication.shared.open(url) }
            else { notice("설정한 서버와 다른 주소입니다. NAS 도메인과 역방향 프록시를 확인해 주세요.") }
        }
        func webView(_ webView:WKWebView,didFailProvisionalNavigation navigation:WKNavigation!,withError error:Error) { notice("서버에 연결하지 못했습니다. HTTPS 인증서, 인터넷 연결과 NAS 실행 상태를 확인해 주세요.") }
        func webView(_ webView:WKWebView,runJavaScriptConfirmPanelWithMessage message:String,initiatedByFrame frame:WKFrameInfo,completionHandler:@escaping(Bool)->Void) {
            guard let controller=presenter else{completionHandler(false);return}
            let alert=UIAlertController(title:"가족 달력",message:message,preferredStyle:.alert)
            alert.addAction(UIAlertAction(title:"취소",style:.cancel){_ in completionHandler(false)})
            alert.addAction(UIAlertAction(title:"확인",style:.default){_ in completionHandler(true)})
            controller.present(alert,animated:true)
        }
        func webView(_ webView:WKWebView,runJavaScriptAlertPanelWithMessage message:String,initiatedByFrame frame:WKFrameInfo,completionHandler:@escaping()->Void) {
            guard let controller=presenter else{completionHandler();return}
            let alert=UIAlertController(title:"가족 달력",message:message,preferredStyle:.alert)
            alert.addAction(UIAlertAction(title:"확인",style:.default){_ in completionHandler()});controller.present(alert,animated:true)
        }
        private var presenter:UIViewController? {
            guard var controller=view?.window?.rootViewController else{return nil}
            while let presented=controller.presentedViewController { controller=presented };return controller
        }
        private func notice(_ message:String) {
            guard let controller=presenter,!(controller is UIAlertController) else{return}
            let alert=UIAlertController(title:"연결 확인",message:message,preferredStyle:.alert)
            alert.addAction(UIAlertAction(title:"확인",style:.default));controller.present(alert,animated:true)
        }
    }
}
