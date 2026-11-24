# Famical 다운로드

[Android v2.1.2 APK 다운로드](famical-android-2.1.2.apk) · [Android 설치 안내](../android/README.md)

[Windows v2.0.1 x64 ZIP 다운로드](famical-windows-2.0.1-x64.zip) · [Windows 설치 안내](../windows/README.md)

Android v2.1.2은 좌우 월 이동 슬라이드와 달력 접기·펼치기의 부드러운 높이 전환을 제공합니다. 접힌 달력 아래에는 선택한 날짜의 일정 3줄을 표시하며 추가 일정은 목록을 스크롤해 확인합니다. 달력을 아래로 밀면 다시 펼칩니다.

일정 막대 색상 16가지를 제공하며, 색상 선택창에서 실제 색상 견본을 확인합니다. 달력 배경에 사용하는 색상값은 선택지에서 제외했습니다.

NAS를 v2.0.1으로 먼저 업데이트하고 기존 데이터 볼륨·관리자 설정 파일을 유지하세요. 앱 설치 후 가족 계정으로 첫 로그인·동기화를 진행합니다. 기본 서버는 `https://famical.ymsh.synology.me/`입니다.

Windows와 Android는 기기에서 달력을 그리며, 기기 저장 일정으로 오프라인 조회·추가·수정·삭제를 지원합니다. 재연결 시 변경을 전송하며, 동시에 수정된 일정은 확인 후 적용합니다. 기존 웹앱도 같은 계정과 일정으로 사용할 수 있습니다.

음력·연간 반복·교대조·회사/가족·공개 범위·평일 연속 일수·인접 달 날짜 표시를 지원합니다. Windows는 크기·투명도 조절, 트레이, Alt+Tab 제외와 간편/상세 입력을 제공합니다. Android는 기존 위젯과 삼성·휴대전화 캘린더 연동을 유지합니다.

APK는 R8·리소스 축소를 적용한 릴리스 빌드이며 기존 APK와 같은 개발용 인증서로 서명했습니다. Windows는 .NET Framework 4.8을 사용합니다.

[체크섬](SHA256SUMS) · [빌드·검증 정보](build-info.json)

서버 자동 테스트, Android Robolectric 테스트, Windows C# 핵심 모델·통신의 Flask 연동 테스트와 웹 브라우저 검증을 완료했습니다. 실제 Windows UI와 삼성 기기에서의 실행은 확인이 필요합니다.

iPhone은 기존 웹앱과 Scriptable 위젯을 사용할 수 있습니다. [Scriptable 위젯 파일](FamicalCalendar.scriptable) · [iPhone 안내](../ios/scriptable/README.md)

이전 Android APK는 [v1.2.1](famical-android-1.2.1-debug.apk), 이전 Windows 웹 화면 앱은 [v1.1.1](famical-windows-1.1.1-x64.zip)에서 받을 수 있습니다.

Android v2.1.2에서는 홈 화면 위젯의 날짜 칸을 누르면 해당 월·날짜를 선택하고 하단에 그날의 일정 목록을 엽니다. 이미 앱이 실행 중이어도 적용됩니다. APK 업데이트 후 위젯의 갱신 버튼을 한 번 누르세요. NAS가 v2.0.1이면 서버 재설치는 필요 없습니다.
