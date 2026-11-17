# iPhone home widget without a Mac: Scriptable

Mac이나 본인 소유 Apple 개발자 계정 없이 iPhone 홈 화면 위젯을 사용하는 방법입니다. App Store의 **Scriptable** 앱에서 Famical 스크립트를 실행합니다. 별도의 Famical 네이티브 IPA를 설치하는 방식은 아닙니다.

## 설치

1. iPhone App Store에서 [Scriptable — Simon Stovring](https://apps.apple.com/kr/app/scriptable/id1405459188)을 설치합니다. 확인 시 한국 App Store 가격은 무료였습니다.
2. [FamicalCalendar.scriptable 다운로드](https://github.com/6k5duh/famical/raw/refs/heads/main/downloads/FamicalCalendar.scriptable)를 받습니다.
3. 파일 앱에서 다운로드한 파일의 공유 메뉴로 Scriptable에서 열기/복사를 선택하고 가져옵니다. 메뉴가 보이지 않으면 아래 수동 방법을 사용합니다.
4. Scriptable에서 **FamicalCalendar**를 실행합니다.
5. NAS 달력이 열리는 HTTPS 도메인, 가족 아이디, 비밀번호를 입력합니다. 비밀번호는 저장하지 않습니다. 로그인에 필요한 세션과 표시 설정은 해당 기기의 Keychain에 저장합니다.
6. 위젯 미리보기가 나오면 홈 화면으로 돌아갑니다.
7. 홈 화면의 빈 곳을 길게 누르고 **위젯 추가 → Scriptable**을 선택합니다. 중간 또는 큰 크기를 배치합니다.
8. 배치한 위젯을 길게 눌러 위젯 편집에서 **Script = FamicalCalendar**를 선택합니다.

파일 가져오기가 안 되는 경우: [FamicalCalendar.js 원본](https://github.com/6k5duh/famical/blob/main/ios/scriptable/FamicalCalendar.js)을 열고 Raw 버튼으로 전체 코드를 복사합니다. Scriptable의 + 버튼으로 새 스크립트를 만들고 붙여 넣어 `FamicalCalendar`로 저장합니다. 채팅에 표시된 코드가 아니라 Raw 원본을 사용하세요.

## 표시와 사용

- 중간·큰 위젯: 월 달력, 교대 음영, 오늘 일정 최대 3개. 작은 위젯: 오늘 일정.
- 위젯 탭: 설정한 NAS 달력을 Safari에서 엽니다. 앱 아이콘처럼 쓰려면 Safari 공유 → 홈 화면에 추가를 사용합니다.
- Scriptable에서 스크립트를 실행하면 미리보기·갱신, 달력 열기, 로그인·서버 변경, 위젯 표시 설정, 연결 해제 메뉴를 선택할 수 있습니다.
- 교대조 A/B/OFF는 스크립트의 **위젯 표시 설정**에서 정합니다. Safari 달력의 설정과 별도로 유지합니다.
- 개인 일정은 기본 숨김입니다. 위젯 표시 설정에서 개인 일정 표시를 켤 수 있습니다. 켜면 본인의 개인 일정 제목이 홈 화면에 보입니다.
- 종료일까지 포함하는 기간 일정, 음력 반복 일정은 NAS API가 해당 양력 날짜에 반환하는 결과를 사용합니다. 위젯 월 달력 자체에는 양력 숫자를 표시합니다.
- 약 30분 뒤 갱신을 요청하지만 iOS가 실제 갱신 빈도를 조절합니다. 실시간 갱신은 보장되지 않습니다.
- 네트워크 실패 시 같은 날짜의 마지막 스냅샷과 실패 상태를 표시합니다. 데이터는 Keychain에 보관하며 로그아웃/연결 해제 시 캐시와 세션을 지웁니다.

## 로그인 주의점

Scriptable 위젯 로그인은 Safari/PWA와 **별도의 로그인 세션**입니다. Safari에서 로그아웃해도 Scriptable 세션이 자동 삭제되지는 않습니다. 위젯에서 사용을 중단하려면 Scriptable → FamicalCalendar 실행 → **연결 해제**를 선택하세요. 로그인 만료 시 스크립트를 다시 실행해 로그인합니다.

스크립트의 네트워크 요청은 설정한 NAS API로만 전송하며 리디렉션을 차단합니다. HTTPS 인증서 오류를 무시하지 않습니다. 기본 계정·비밀번호나 서버 주소는 코드에 들어 있지 않습니다. 실제 도메인·아이디·비밀번호를 GitHub 또는 채팅에 올리지 마세요.

## 검증 범위

Scriptable 공식 Request·Keychain·ListWidget API 문서를 확인해 작성했습니다. URL 제한, CSRF 로그인 흐름, 비밀번호 미저장, 개인 일정 필터와 세션 만료 처리 및 위젯 구성은 모의 Scriptable API로 테스트했습니다. 실제 iPhone의 파일 가져오기·위젯 배치·키보드·운영체제 갱신 동작은 기기에서 확인해야 합니다.

이 방식은 별도 App Store 앱인 Scriptable 설치가 필요합니다. 자체 Famical iOS 앱을 원하면 [네이티브 iOS 소스와 Apple 서명 안내](../README.md)를 참고하세요.
