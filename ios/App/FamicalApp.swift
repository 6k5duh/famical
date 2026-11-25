import SwiftUI
import WebKit
import WidgetKit

@main
struct FamicalApp: App {
    var body: some Scene { WindowGroup { CalendarScreen() } }
}

struct CalendarScreen: View {
    @Environment(\.scenePhase) private var phase
    @State private var server=SharedStore.server
    @State private var draft=SharedStore.server?.absoluteString ?? ""
    @State private var settings=SharedStore.server == nil
    @State private var error=""
    @State private var includePrivate=SharedStore.includePrivate
    @State private var reload=0
    @State private var sync=0
    var body: some View {
        NavigationStack {
            Group {
                if let server,!settings {
                    CalendarBrowser(server:server,reload:reload,sync:sync)
                } else {
                    Form {
                        Section("NAS 캘린더 연결") {
                            TextField("https://calendar.example.com",text:$draft).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                            Text("달력이 열리는 HTTPS 도메인을 입력하세요. DSM 관리 화면 주소가 아닙니다.").font(.footnote)
                            if !error.isEmpty { Text(error).foregroundStyle(.red) }
                            Button("연결하기") { connect() }
                        }
                        Section("홈 화면 위젯") {
                            Toggle("위젯에 나의 개인 일정 표시",isOn:$includePrivate)
                                .onChange(of:includePrivate) { _,value in
                                    SharedStore.defaults.set(value,forKey:"includePrivate");SharedStore.snapshot=nil
                                    WidgetCenter.shared.reloadAllTimelines()
                                }
                            Text("앱에서 로그인한 뒤 홈 화면의 위젯 추가에서 달력을 선택하세요. 운영체제가 갱신 빈도를 조절하므로 실시간 표시와 차이가 있을 수 있습니다.").font(.footnote)
                        }
                        Section { Text("iOS 앱 v1.0.0 · 유효한 Apple 서명과 App Groups·Keychain Sharing 설정이 필요합니다.").font(.footnote) }
                    }
                }
            }
            .navigationTitle("달력").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement:.topBarLeading) {
                    if server != nil { Button(settings ? "달력" : "새로고침") { if settings { settings=false } else { reload += 1 } } }
                }
                ToolbarItem(placement:.topBarTrailing) { Button("서버") { draft=server?.absoluteString ?? "";settings=true;error="" } }
            }
            .onChange(of:phase) { _,value in if value == .inactive || value == .background { sync += 1 } }
            .onOpenURL { url in if url.scheme == "famical" { settings=server == nil;sync += 1 } }
        }
    }
    private func connect() {
        guard let next=ServerAddress.normalize(draft) else { error="경로·로그인 정보를 제외한 HTTPS 도메인을 입력해 주세요.";return }
        Task { @MainActor in
            if SharedStore.server != next {
                await WKWebsiteDataStore.default().removeData(ofTypes:WKWebsiteDataStore.allWebsiteDataTypes(),modifiedSince:.distantPast)
            }
            SharedStore.setServer(next);server=next;settings=false;error="";reload += 1
            WidgetCenter.shared.reloadAllTimelines()
        }
    }
}
