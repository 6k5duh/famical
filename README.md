# 우리 가족 달력

Synology DS920+에서 Docker로 실행하는 가족 공유 캘린더입니다. 갤럭시·iPhone 브라우저에서 사용하고 홈 화면에 추가할 수 있습니다.

## 기능

- 4명 이상 가족 계정 로그인, 일정별 모두에게 / 나에게만 공개 범위 선택
- 전체 일정 / 내가 등록한 일정 보기
- 작성자·관리자의 공유 일정 수정 및 삭제 (개인 일정은 작성자만 조회·수정·삭제)
- A/B조 주·야간 자동 표시와 ON/OFF
- 날짜 숫자 옆 한국 음력 표시, 일정별 양력·음력 선택, 매년 반복
- 시작일·종료일을 포함한 기간 일정, 5가지 색상 막대 및 주·월 경계 연결 표시
- 휴대전화 화면 대응 및 홈 화면 추가용 PWA 설정

교대는 월요일마다 바뀝니다. A조 기준 2026년 11월 16~20일은 야간(연한 노랑), 23~27일은 주간(연한 초록)입니다. B조는 반대이며 주말은 분홍색으로 표시합니다. 음력 지원 범위는 1900~2050년입니다. 윤달 또는 음력 30일이 없는 해에는 해당 반복 일정을 건너뜁니다.

## NAS 설치

자세한 HTTPS 외부 접속, 계정 생성, 백업·복구 절차는 [설치안내.md](설치안내.md)를 참고하세요.

```sh
docker compose up -d --build
docker exec -it family-calendar python app.py add-user parent1 --name '가족1' --admin
```

비밀번호는 대화형 입력으로 설정합니다. 기본 계정·비밀번호는 없습니다. 가족 계정은 `add-user` 명령으로 추가합니다.

기본 Compose 설정은 NAS의 `127.0.0.1:8090`에만 연결됩니다. DSM의 HTTPS 역방향 프록시를 통해 접속합니다. 로그인 쿠키는 기본적으로 HTTPS에서 전송합니다. 데이터는 `family-calendar-data` Docker 볼륨에 저장됩니다.

## 로컬 실행

Python 3.12 기준입니다. 아래 HTTP용 쿠키 설정은 로컬 개발용입니다.

```sh
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
export DATA_DIR="$PWD/data"
export COOKIE_SECURE=false
python app.py add-user parent1 --name '가족1' --admin
gunicorn --bind 127.0.0.1:3000 --workers 1 --threads 4 app:app
```

접속 주소: http://127.0.0.1:3000

## 테스트

```sh
python -m unittest -v test_app
node --test test_calendar_layout.mjs
```

테스트 계정과 일정은 임시 디렉터리에 생성합니다. 실제 계정, 데이터베이스, 세션 서명 키는 저장소에 포함하지 않습니다.

## 구조

```text
app.py              Flask API, 인증, SQLite, 음력 변환
templates/          HTML 화면
static/             JavaScript, CSS, PWA 아이콘과 manifest
test_app.py         권한, 공유 일정, 반복 일정 등의 테스트
requirements.txt    Python 의존성
Dockerfile          컨테이너 이미지
compose.yaml        NAS 실행 설정
설치안내.md          설치, 외부 접속, 백업·복구 안내
```

화면이 열려 있는 동안 30초 간격으로 동기화합니다. 알림, 오프라인 편집, 공휴일 자동 표시는 포함하지 않습니다. ‘내 일정’은 작성자 기준 필터이며, 공개 범위 기본값은 모두에게 보이기입니다. ‘나에게만’ 일정은 관리자에게도 노출되지 않습니다.

## 기간 일정과 업데이트

종료일까지 포함해 최대 366일의 일정을 등록할 수 있습니다. 종료일을 비우면 하루 일정입니다. 시작·종료일 모두 선택한 양력/음력 기준이며, 윤달 선택은 두 날짜에 함께 적용됩니다. 매년 반복 시 양 끝 날짜가 모두 존재하는 해에만 표시합니다. 반복 일정의 수정·삭제는 시리즈 전체에 적용됩니다.

기존 버전의 계정과 일정은 그대로 유지합니다. 최초 기동 시 기존 일정의 종료일을 시작일로 설정하고, 공개 범위는 모두에게 보이기, 막대 색은 초록으로 이전합니다. 업데이트 전 데이터 볼륨을 백업하고 최신 소스를 받은 다음 `docker compose up -d --build`로 다시 빌드하세요. 데이터 볼륨을 지우는 `down -v`는 사용하지 마세요.
