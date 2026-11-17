# Famical iOS app + WidgetKit

iPhone에서 NAS 가족 달력을 여는 SwiftUI/WKWebView 앱과 월 달력·오늘 일정·교대조를 표시하는 WidgetKit 확장입니다. iOS 17 이상(iPhone 15 Pro 포함)을 대상으로 합니다.

**이 폴더는 소스 코드입니다. 서명된 IPA 또는 TestFlight 설치 링크는 아직 제공하지 않습니다.** APK는 iPhone에 설치할 수 없습니다. GitHub Actions의 iOS Simulator 빌드도 실제 iPhone 설치 파일이 아닙니다.

## Mac이 없는 경우

현재 바로 사용할 수 있는 방법은 기존 HTTPS 달력을 Safari에서 열고 공유 → 홈 화면에 추가하는 웹앱입니다. 웹앱 홈 화면 아이콘은 네이티브 WidgetKit 위젯과 다릅니다.

물리적인 Mac이 없어도 클라우드 Mac/CI에서 iOS 앱을 빌드할 수 있습니다. 그러나 실제 기기 설치·TestFlight 배포에는 Apple의 유효한 서명 인증서와 프로비저닝 프로파일이 필요합니다. 이 앱의 App Groups·Keychain Sharing 및 TestFlight를 사용하려면 Apple Developer Program 설정이 필요합니다. 현재 포함된 GitHub Actions는 **서명 없는 시뮬레이터 컴파일 검증**만 수행합니다. Apple 개발자 계정, 서명 인증서, App Store Connect 업로드 설정은 포함하지 않습니다.

## 동작

- 앱에서 NAS의 HTTPS 도메인을 설정하고 기존 가족 계정으로 로그인합니다.
- 앱은 비밀번호를 별도로 저장하지 않습니다. 위젯의 API 조회에 필요한 해당 NAS의 `session` 쿠키만 공유 Keychain에 저장합니다.
- 서버 주소·표시 설정·최신 일정 스냅샷은 App Group 컨테이너로 공유합니다.
- Keychain은 `AfterFirstUnlockThisDeviceOnly`를 사용합니다. 기기 재부팅 직후 첫 잠금 해제 전에는 인증 정보에 접근하지 못할 수 있습니다.
- 서버를 바꾸거나 로그인 쿠키가 바뀌면 이전 위젯 캐시를 지웁니다. 로그아웃·만료 시 위젯은 재로그인을 안내합니다.
- 위젯은 기존 `/api/events`를 사용하므로 NAS 서버 소스 변경이 필요하지 않습니다. 개인 일정에 대한 서버 권한도 그대로 적용됩니다.
- 개인 일정 제목은 위젯에서 기본 숨김입니다. 앱의 서버 화면에서 ‘위젯에 나의 개인 일정 표시’를 켜면 본인에게 권한이 있는 개인 일정도 표시합니다.
- 작은 위젯은 오늘 일정, 중간·큰 위젯은 월 달력과 오늘 일정을 표시합니다. 주·야간 음영은 앱에서 선택한 조와 ON/OFF를 따릅니다. 위젯 탭으로 앱을 엽니다.
- 갱신 요청은 약 30분 간격이지만 iOS가 빈도를 조절합니다. 즉시 동기화는 보장되지 않습니다. 네트워크 실패 시 같은 날짜의 마지막 데이터를 표시하고 갱신 실패를 안내합니다.

## Mac 또는 클라우드 Mac에서 컴파일

Xcode 16 이상과 XcodeGen이 필요합니다.

```sh
brew install xcodegen
cd ios
xcodegen generate
xcodebuild -project Famical.xcodeproj -scheme Famical -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
```

이 결과는 iOS Simulator 전용입니다. 실제 iPhone 설치용으로 이름만 IPA로 바꾸어도 설치할 수 없습니다.

핵심 날짜·주소 정책 테스트:

```sh
swift test --package-path ios/Core
```

위 테스트는 저장소 루트에서 실행합니다. 날짜 계산과 서버 출처 제한만 검사하며 실제 WidgetKit·기기 서명 동작까지 검증하지 않습니다.

## 실제 iPhone 서명 및 위젯 설정

1. Apple 개발자 계정에서 앱과 위젯 확장의 App ID, App Group을 준비합니다.
2. `project.yml`의 `FAMICAL_BUNDLE_PREFIX`를 본인 소유의 고유 번들 식별자로 변경합니다. 예: `com.yourname.famical`. 위젯은 자동으로 `.widget` 접미사를 사용합니다.
3. `FAMICAL_APP_GROUP`도 본인 계정에 등록한 그룹(예: `group.com.yourname.famical`)으로 변경합니다.
4. 설정의 `DEVELOPMENT_TEAM`을 본인의 Team ID로 지정하거나, 프로젝트 생성 후 Xcode에서 앱·위젯 두 타깃의 Signing Team을 같은 계정으로 설정합니다.
5. 두 타깃 모두 같은 **App Groups**와 **Keychain Sharing** 권한을 사용해야 합니다. `FAMICAL_KEYCHAIN_GROUP`은 App Identifier Prefix와 앱 번들 접두사를 조합합니다. 생성된 entitlements 및 Info.plist 값이 양쪽에서 일치해야 합니다.
6. 자동 서명 또는 본인의 인증서·프로파일로 실제 기기 빌드/Archive를 수행합니다. TestFlight는 App Store Connect 업로드와 베타 배포 설정까지 필요합니다.
7. 서명된 앱을 설치하고 서버 설정·로그인 후 홈 화면을 길게 눌러 위젯 추가 → 우리 가족 달력을 선택합니다.

인증서 `.p12`, 개인 키, 프로비저닝 프로파일, App Store Connect 키는 저장소에 올리지 않습니다. 클라우드 서명 파이프라인을 추가할 때는 GitHub Secrets 등 비밀 저장소를 사용해야 합니다.

## 검증 한계

현재 Linux 실행 환경에서는 Xcode를 실행하지 못합니다. 포함된 CI 결과는 저장소 Actions에서 확인해야 하며, 성공해도 Apple 서명·실제 iPhone·WidgetKit 갱신 예산·키보드·App Group 권한의 기기 검증은 별도로 필요합니다. 위젯은 홈 화면용이며 배경화면 이미지 자체를 변경하는 기능은 아닙니다.
