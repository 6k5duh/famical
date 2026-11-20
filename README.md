# Famical v2.0.1 — 우리 가족 달력

Synology DS920+에서 Docker로 실행하고, 갤럭시·iPhone 브라우저와 홈 화면 앱으로 함께 쓰는 가족 캘린더입니다.

Windows와 Android는 네이티브 달력·기기 저장·오프라인 편집·재연결 동기화를 지원합니다. NAS 웹앱도 같은 계정과 일정으로 사용할 수 있습니다.

[Windows 설치 파일과 안내](windows/README.md) · [Android APK와 안내](android/README.md)

기존 사용자는 NAS를 v2.0.1으로 먼저 업데이트한 뒤 각 앱을 설치합니다. 기존 데이터 볼륨과 `initial-admin.env`를 유지하고 앱에서 첫 로그인·동기화를 진행하세요.

**처음 설치:** [Synology A to Z 설치 안내](setupmanual.md)

**안드로이드 앱·위젯:** [APK 설치 및 사용 안내](android/README.md) · [APK 다운로드 폴더](downloads/)

**iPhone 앱·위젯 소스:** [iOS 설치·서명 조건 및 빌드 안내](ios/README.md) — 서명된 iPhone 설치 파일은 아직 제공하지 않습니다.

현재 구성은 폴더 `docker/famical`, 이미지 `famical:2.0.1`, 컨테이너 `famical`, 데이터 볼륨 `famical-data`를 사용합니다. 이전 버전의 `family-calendar-data`는 자동으로 연결하지 않습니다. 기존 계정·일정을 유지하려면 볼륨을 별도로 이전해야 하며, 이 안내서는 새 설치 기준입니다.

## 기능

- 가족별 로그인, 관리자 화면에서 추가 가족 계정 생성
- 초기 설정 파일로 최초 관리자 생성 (기본 비밀번호 없음)
- 일정별 모두에게 / 나에게만 공개 범위 선택, 전체/내 일정 보기
- 개인 일정은 관리자에게도 숨김, 공유 일정은 작성자·관리자가 수정·삭제
- 종료일을 포함한 최대 366일 기간 일정과 16가지 색상 막대
- A/B조 평일 교대 음영과 ON/OFF, 사진에 맞춘 주말 분홍색
- 날짜 숫자 옆 한국 음력, 양력·음력 선택과 매년 반복
- 로그인 전후 화면 하단 버전 표시, `/api/version` 상태 확인

## Docker 시작

`initial-admin.env.example`을 `initial-admin.env`로 복사하고 본인 관리자 아이디·표시 이름·12자 이상 비밀번호를 설정한 뒤 실행합니다. 실제 비밀번호 파일은 GitHub에 올리지 않습니다.

```sh
cp initial-admin.env.example initial-admin.env
# Edit initial-admin.env and set your own INITIAL_ADMIN_PASSWORD.
docker compose up -d --build
```

기본 연결은 NAS의 `127.0.0.1:8090`이며 HTTPS 역방향 프록시로 접속합니다. 자세한 DNS·인증서·로그인·가족 계정 생성 순서는 설치 안내를 참고하세요.

## 로컬 개발

Python 3.12 기준입니다. 아래 쿠키 설정은 로컬 HTTP 개발용입니다.

```sh
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
export DATA_DIR="$PWD/data"
export COOKIE_SECURE=false
python app.py add-user admin --name '관리자' --admin
gunicorn --bind 127.0.0.1:3000 --workers 1 --threads 4 app:app
```

http://127.0.0.1:3000 에 접속합니다. 관리자 로그인 후 가족 계정 관리에서 계정을 추가합니다. 초기 환경 변수는 계정이 하나도 없을 때만 사용하며 기존 비밀번호를 덮어쓰지 않습니다.

## 테스트

```sh
python -m unittest -v test_app
node --test test_calendar_layout.mjs
```

테스트는 임시 디렉터리와 합성 계정으로 실행합니다. 실제 비밀번호·일정 DB·세션 키는 저장소에 포함하지 않습니다.

## 제한

한국 음력은 1900~2050년을 지원하며, 해당 음력 날짜·윤달이 없는 해는 건너뜁니다. 기간 일정 양 끝에 같은 양력/음력 기준과 윤달 여부를 적용합니다. 반복 일정 수정·삭제는 시리즈 전체에 적용합니다. 웹 계정 관리는 목록 조회·신규 생성 기능입니다. 알림·오프라인 편집·공휴일 자동 표시는 포함하지 않습니다.
