# Famical for Windows

[Windows x64 다운로드](../downloads/famical-windows-1.0.0-x64.zip)

1. NAS 웹앱을 v1.6.0 이상으로 업데이트합니다.
2. Windows 10/11 x64 PC에서 ZIP 전체를 한 폴더에 압축 해제한 뒤 `Famical.exe`를 실행합니다. .NET Framework 4.8과 [Microsoft Edge WebView2 Evergreen Runtime](https://developer.microsoft.com/microsoft-edge/webview2/)이 필요합니다.
3. 기본 가족 서버로 자동 연결되면 가족 계정으로 로그인합니다. 개인 PC에서는 로그인 화면에서 90일 자동 로그인을 선택할 수 있습니다.

왼쪽 위 FAMICAL을 드래그해 창을 옮기고, 테두리나 오른쪽 아래 모서리를 드래그해 크기를 바꿉니다. 상단의 투명도 슬라이더는 0~60% 범위로 조절됩니다. 창을 닫을 때 크기와 투명도를 저장합니다. 다른 서버를 사용하려면 앱을 닫고 `%LOCALAPPDATA%\Famical\window.xml`의 `server` 값을 원하는 HTTPS 서버 주소로 변경하세요.

일정 추가·수정 화면에서 가족 또는 회사를 선택합니다. 달력 상단의 일정 구분 필터와 내 일정 필터를 함께 사용할 수 있습니다. 공개 범위는 일정마다 별도로 선택합니다.

## 소스에서 빌드

.NET 8 SDK로 저장소 루트에서 `dotnet build windows/Famical.csproj -c Release`를 실행합니다. 배포할 때는 `windows/bin/Release/net48`의 실행 파일, 설정 파일, DLL 및 runtimes 폴더를 함께 제공합니다.
