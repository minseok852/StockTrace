# StockTrace 공시 API

Kotlin/JVM 21로 실행하는 로컬 서버다. Android 앱은 HTTP로 공시를 조회한다. 현재 상세 조회의 데이터 소스는 프로젝트의 원문 대조 도구로 내려받은 OpenDART ZIP이다.

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

## 공시 상세

`GET /api/v1/disclosures/{receiptNo}`는 보고서의 직전·이번 보유 수량, 원문 순서의 변동 행과 DART 링크를 반환한다. 정정 보고서는 정정 후 본문을 사용한다. 시세와 정정 이력은 아직 연결 전이므로 `comparisonQuote`와 `latestReceiptNo`는 null이다.

잘못된 접수번호 형식은 400, 준비된 폴더에 없는 접수번호는 404, 폴더나 원문을 읽을 수 없으면 503이다. 현재 표본 범위는 내려받은 접수번호에 한정된다. 전체 시장 목록은 90일 수집과 정정·철회 대조가 필요한 후속 기능이다.
