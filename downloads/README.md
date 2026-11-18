# Android APK download

[**최신 APK v1.2.1 다운로드**](https://github.com/6k5duh/famical/raw/refs/heads/main/downloads/famical-android-1.2.1-debug.apk)

이번 v1.2.1은 기본 서버 자동 연결 업데이트입니다. 처음 실행하거나 저장된 서버 주소가 없으면 **설정된 가족 서버**에 바로 연결합니다. 기존에 직접 저장한 주소가 있으면 해당 주소로 자동 연결하며, 상단 **서버**에서 변경할 수 있습니다. 가족 계정 로그인은 필요합니다.

이미 NAS 웹앱 v1.3.0을 사용 중이면 이번에는 APK만 업데이트하세요. 캘린더 양방향 연동·교대조 계정 저장에는 NAS v1.3.0 이상이 필요합니다.

- 삼성·휴대전화 캘린더의 일반 일정 양방향 연동: 추가·수정·삭제
- 선택한 캘린더 하나와 본인 일정만 연결, 가져오는 일정의 공개 범위 선택
- 반복 일정은 발생일별 가져오기, Famical 음력·연간 일정은 휴대전화에 보기용 표시
- 동시에 수정한 일정은 보류하고 사용자가 우선할 쪽을 선택
- 교대조 **해당 없음 (주간)** 추가 및 계정 저장: 재로그인·다른 기기에서 유지
- 일정 막대·오늘 빨간 테두리·1시간 위젯 갱신 요청 유지

[**업데이트·연동 설정 안내**](../android/CALENDAR_SYNC.md) · [체크섬](SHA256SUMS) · [빌드 정보](build-info.json)

Android 모의 캘린더·서버 자동 테스트를 통과했습니다. 실제 삼성 기기·삼성 클라우드 연동은 확인이 필요합니다. 반복 원본 규칙 자체를 양쪽에서 편집하는 기능은 지원하지 않습니다.

iPhone Scriptable 위젯도 계정의 교대조 설정을 읽도록 업데이트했습니다. 기존 스크립트를 같은 이름으로 교체하세요.

[Scriptable 위젯 파일](FamicalCalendar.scriptable) · [iPhone 설치 안내](../ios/scriptable/README.md)

이전 APK: [v1.2.0](famical-android-1.2.0-debug.apk) · [v1.1.2](famical-android-1.1.2-debug.apk) · [v1.1.1](famical-android-1.1.1-debug.apk) · [v1.1.0](famical-android-1.1.0-debug.apk) · [v1.0.0](famical-android-1.0.0-debug.apk)
