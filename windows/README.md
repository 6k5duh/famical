# Famical for Windows

[Windows x64 다운로드](../downloads/famical-windows-1.0.0-x64.zip)

1. NAS 웹앱을 v1.7.0 이상으로 업데이트합니다.
2. Windows 10/11 x64 PC에서 ZIP 전체를 한 폴더에 압축 해제한 뒤 `Famical.exe`를 실행합니다. .NET Framework 4.8과 [Microsoft Edge WebView2 Evergreen Runtime](https://developer.microsoft.com/microsoft-edge/webview2/)이 필요합니다.
3. 기본 가족 서버로 자동 연결되면 가족 계정으로 로그인합니다. 개인 PC에서는 로그인 화면에서 90일 자동 로그인을 선택할 수 있습니다.

왼쪽 위 FAMICAL을 드래그해 창을 옮기고, 테두리나 오른쪽 아래 모서리를 드래그해 크기를 바꿉니다. 상단의 투명도 슬라이더는 0~60% 범위로 조절됩니다. 창을 닫을 때 크기와 투명도를 저장합니다. 다른 서버를 사용하려면 앱을 닫고 `%LOCALAPPDATA%\Famical\window.xml`의 `server` 값을 원하는 HTTPS 서버 주소로 변경하세요.

날짜 칸 또는 **+ 일정 추가**를 누른 뒤 한 줄에 하나씩 입력하고 오른쪽 위 ✓를 누르면 저장됩니다. `14:00 생산 워크숍`처럼 앞에 시간을 붙일 수 있습니다. Windows에서 추가하는 일정의 기본값은 **회사 · 나에게만 보이기**입니다. 하단 **상세**에서 구분, 공개 범위, 기간, 반복, 색상, 메모를 바꿀 수 있습니다. 한 번에 여러 줄을 작성하면 선택한 상세 설정이 각 줄에 적용됩니다.

달력은 창 높이에 맞춰 한 달 전체를 표시합니다. 날짜의 **+N개**를 누르면 그날 일정 전체를 볼 수 있습니다. **설정**에서 일정 구분·작성자 필터, 교대조와 계정 메뉴를 이용할 수 있습니다.

## 소스에서 빌드

.NET 8 SDK로 저장소 루트에서 `dotnet build windows/Famical.csproj -c Release`를 실행합니다. 배포할 때는 `windows/bin/Release/net48`의 실행 파일, 설정 파일, DLL 및 runtimes 폴더를 함께 제공합니다.
