# StockTrace 공시 API

Kotlin/JVM 21로 실행하는 로컬 서버다. Android 앱은 HTTP로 공시를 조회한다.

## 실행

새 체크아웃에서는 프로젝트 루트의 `.env`에 `OPENDART_API_KEY`를 설정한 뒤 표본을 준비하고 서버를 시작한다.

```powershell
powershell -NoProfile -File .\tools\check-dart.ps1 -All
.\gradlew.bat -p backend run
```

기본 주소는 `http://127.0.0.1:8080`이다. `PORT`로 포트를, `DART_ARCHIVE_DIR`로 ZIP 폴더의 절대 경로를 지정할 수 있다. 기본 ZIP 폴더는 프로젝트 루트의 `build/dart-check`다. 서버 실행 후 다른 PowerShell 창에서 조회한다.

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/disclosures/20260731000755
```

## 시장 지표

서버를 시작하는 프로세스의 환경 변수 `DATA_GO_KR_SERVICE_KEY`에 금융위원회 지수시세정보의 디코딩 키를, `KOREAEXIM_AUTH_KEY`에 한국수출입은행 인증키를 설정한다. 서버는 첫 조회 시 지표를 수집하고 메모리에서 결과를 공유한다. 세 행 모두 정상일 때 한 시간, 일부 또는 전체 실패 시 5분이 지나면 다음 요청에서 다시 수집한다. 서버 재시작 시 보관값은 초기화된다. 지수는 최근 30일 범위에서 종합지수 이름을 정확히 일치시킨다. 환율은 한국 날짜로 오늘부터 14일 전까지 조회해 최근 두 고시값을 찾는다. 비교값만 확인할 수 없으면 차액은 null이다.

```powershell
.\gradlew.bat -p backend run
# 다른 창에서 상태와 JSON 확인
curl.exe -si http://127.0.0.1:8080/api/v1/market/indicators
curl.exe -si -X POST http://127.0.0.1:8080/api/v1/market/indicators
```

두 키가 없는 새 프로세스에서 GET은 503, POST는 405다. 공급처 대역으로 정상·부분 실패 응답을 확인하려면 `.\gradlew.bat -p backend test --tests '*backend.market.*'`를 실행한다.

## 공시 상세

`GET /api/v1/disclosures/{receiptNo}`는 보고서의 직전·이번 보유 수량, 원문 순서의 변동 행과 DART 링크를 반환한다. 정정 보고서는 정정 후 본문을 사용한다. 시세와 정정 이력은 아직 연결 전이므로 `comparisonQuote`와 `latestReceiptNo`는 null이다.

잘못된 접수번호 형식은 400, 저장된 공시가 없으면 404, 폴더나 원문을 읽을 수 없으면 503이다. 목록에 있고 공급처에서 원문을 제공하지 않은 공시는 기본 정보와 원문 링크를 반환한다.

## 공시 목록

프로젝트 루트에서 아래 명령을 순서대로 실행한다. 수집기는 최초 실행일을 포함한 최근 90일의 상장사 D002 접수분을 조회한다. 후속 실행에서는 최초 시작일부터 다시 대조해 과거 보고서에 대한 정정과 철회를 반영한다. 저장된 원문 ZIP은 재사용한다.

```powershell
.\gradlew.bat -p backend test
.\gradlew.bat -p backend run --args=collect
.\gradlew.bat -p backend run
```

수집기는 전체 기간의 접수 목록과 최종보고서 목록을 대조한 뒤, 이미 받은 ZIP의 거래 정보를 반영해 목록 파일을 교체한다. 기간 중 목록 조회가 실패하면 이전 파일을 유지한다. 실행 중인 서버는 재시작할 때 새 파일을 읽는다.

목록 저장 후에는 빠진 원문 ZIP을 요청한다. 연결이 끊기면 1초, 2초, 4초 뒤에 재시도하고, 계속 실패하면 해당 접수번호를 출력한 뒤 그때까지 확보한 원문을 목록에 반영한다. 원문이 없는 공시는 목록에 남고 보유량 변동 방향은 `UNKNOWN`이다. 마지막 로그의 ‘미확보’ 건수에는 OpenDART가 원문 미제공(014)으로 응답한 건도 포함된다. 재실행하면 저장된 ZIP을 재사용한다.

기업코드 ZIP은 한국 날짜 기준 같은 날의 재실행에서 재사용한다. JVM에서 OpenDART 연결이 반복해서 끊기면 프로젝트 루트에서 `powershell -NoProfile -File .\tools\resume-dart-collection.ps1`을 실행한다. Kotlin 수집기가 기업코드, 공시 목록, 개별 원문을 모두 Windows PowerShell을 통해 요청한다. 두 실행 방식은 같은 목록과 ZIP 저장소를 사용한다.

목록 요청에서 연결이 끊기면 `powershell -NoProfile -File .\tools\resume-dart-collection.ps1 -CheckList`로 인증된 목록 요청 한 번을 검사한다. 이 모드는 수집기를 시작하지 않는다. 2026년 9월 22일 D002 목록의 첫 접수번호와 상태 000이 나오면 해당 요청이 성공한 것이다.

```powershell
$list = 'http://127.0.0.1:8080/api/v1/disclosures'
curl.exe -si "${list}?limit=2"
curl.exe -si "${list}?days=30&direction=INCREASE"
curl.exe -si "${list}?limit=51"
```

성공 응답은 200이며 `period`, `counts`, `items`, `nextCursor`를 포함한다. 완료된 기간에서 일치하는 공시가 없으면 200과 빈 배열이다. 범위가 준비되지 않았거나 종목의 발행사를 확인할 수 없으면 503, 잘못된 필터나 페이지 크기는 400, 서버가 보관하던 페이지가 만료되면 410이다.

원문 ZIP 또는 내부 XML 형식 오류는 접수번호별로 기록한다. 목록에는 해당 공시의 기본 정보가 남고 변동 방향은 `UNKNOWN`이다. 실행 마지막의 ‘원문 해석 보류’는 ZIP 확보 건수와 별도로 집계한다.

## 공시 목록 HTTP 부하 시험

목록이 준비된 서버를 실행한 뒤 프로젝트 루트에서 Node.js 22 이상으로 `node tools/bench-disclosures.mjs`를 실행한다. 기본 포트는 8080이며 서버의 포트를 바꾼 경우 같은 `PORT` 환경 변수를 지정한다. 별도 인증키 없이 로컬 StockTrace 서버에만 요청한다.

| 시나리오 | 요청 | 목표 | 예정 요청 수 |
|---|---|---|---|
| 평상시 목록 조회 | `GET /api/v1/disclosures?limit=20` | 60초간 100 TPS | 6,000 |
| 홈 접속 집중 | `GET /api/v1/disclosures?limit=2` | 10초간 500 TPS | 5,000 |

출력의 `sentTpsInWindow`와 `successTpsInWindow`는 각각 구간 안에 전송한 요청과 본문 확인까지 마친 성공 응답을 구간 길이로 나눈 값이다. `successTpsIncludingDrain`은 마지막 응답까지 포함한다. 성공 응답의 p50·p95·p99 지연은 밀리초로 표시한다. `failed`는 시작 후 실패한 요청 수, `missed`는 구간 내 시작하지 못한 요청 수다. 이 시험은 공시 목록 한 경로의 처리량을 측정한다. 전체 홈 API 조합과 동시 사용자 1,000명의 세션 동작은 별도 시험 범위다.

## 성공·실패 응답 테스트 (Windows PowerShell)

위 실행 절차로 서버를 띄운 뒤, 두 번째 PowerShell 창에서 아래 명령을 실행한다. `curl.exe -i`는 HTTP 상태, 헤더, JSON 본문을 함께 보여준다. 아래 주소는 기본 포트 8080 기준이다.

```powershell
$api = 'http://127.0.0.1:8080/api/v1/disclosures'
curl.exe -sS -i "$api/20260731000755"
curl.exe -sS -i "$api/abc"
curl.exe -sS -i "$api/00000000000000"
curl.exe -sS -i -X POST "$api/20260731000755"
```

| 요청 | 기대 상태 | 본문·헤더 확인 |
|---|---|---|
| 준비한 노태문 공시 조회 | 200 | `receiptNo="20260731000755"`, `holdings.deltaShares=3045`, `holdings.changePercent=2.51`. 첫 거래 행의 `unitPriceKrw=230000` |
| `abc` 조회 | 400 | `code="INVALID_ARGUMENT"` |
| ZIP 폴더는 정상이나 해당 접수번호 파일이 없음 | 404 | `code="NOT_FOUND"` |
| 정상 경로에 POST | 405 | `code="METHOD_NOT_ALLOWED"`, 응답 헤더 `Allow: GET` |

**503 재현**. 첫 번째 창에서 서버를 Ctrl+C로 종료한다. 프로젝트 루트에서 아래 명령으로 존재하지 않는 임시 경로를 지정해 다시 실행한다. 기존 ZIP은 그대로 보관된다.

```powershell
$previousArchiveDir = $env:DART_ARCHIVE_DIR
$env:DART_ARCHIVE_DIR = Join-Path $env:TEMP ([guid]::NewGuid().ToString())
try {
    .\gradlew.bat -p backend run
} finally {
    $env:DART_ARCHIVE_DIR = $previousArchiveDir
}
```

두 번째 창에서 `curl.exe -sS -i "$api/20260731000755"`를 다시 실행한다. 기대값은 **503**과 `code="SERVICE_UNAVAILABLE"`이다. 모든 오류 응답에는 비어 있지 않은 `message`와 `requestId`가 있어야 한다. 연결 자체가 실패하면 먼저 서버 시작 로그와 포트를 확인한다. 503 확인 후 첫 번째 창에서 Ctrl+C로 종료하고 평소 실행 명령으로 다시 시작한다. 같은 접수번호가 200으로 돌아오는지 확인한다.

이미 받은 ZIP 표본의 거래 행만 다시 대조하려면 프로젝트 루트에서 `powershell -NoProfile -File .\tools\check-dart.ps1 -All -UseSavedZip`을 실행한다. 이 명령은 서버 없이 원문 데이터를 검사하며, 위 HTTP 응답 검사는 별도로 실행한다.
