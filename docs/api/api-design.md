# StockTrace Android API 설계 초안

2026-11-27 · Kotlin 서버와 Android Compose 사이의 제안 명세

검색 대상은 코스피·코스닥·코넥스의 국내 상장 주식 전체다. 전체 상장기업의 임원·주요주주 특정증권등 소유상황보고(D002)는 최초 수집일(한국 날짜) 포함 최근 90일의 접수분을 먼저 채우고 이후 접수분을 계속 누적한다. 최초 수집일 89일 전이 서비스 이력의 고정 시작일이다.

첫 구현 범위는 **홈 최근 공시 → 공시 목록 → 공시 상세 → DART 원문**이다. 목록 한 항목은 보고서 한 건이고, 상세의 변동 내역 한 행은 그 보고서에 기재된 개별 변동이다. 이 구분을 유지하면 수증과 장내매수가 섞이거나 같은 날 여러 단가로 매수한 보고서도 표현할 수 있다.

화면 기준은 [전체 앱 프로토타입](https://www.figma.com/design/vffh5rjHdioDiQrClJd0XX/StockTrace?node-id=87-874)이다. 표본의 출처와 검증 범위는 [공시 단가 검증 기록](./공시-단가-검증.md)에 있다. 아래 경로와 필드명은 구현을 위한 제안이다.

## 1. 화면에서 호출까지

모든 REST 경로의 공통 접두사는 `/api/v1`이다. 시세·공시 조회는 로그인 전에도 제공한다. 홈에서는 각 영역을 독립적으로 로드해 지수나 환율 조회에 실패해도 최근 공시를 열 수 있게 한다.

| 화면과 사용자 동작 | 요청 | 화면에서 쓰는 값 |
|---|---|---|
| 홈에서 시장 지표 확인 | `GET /market/indicators` | 코스피·코스닥의 지수값과 전일 대비 %, 원/달러의 1달러당 원화와 전일 대비 원화 차이, 지표별 기준 시각 |
| 홈 인기 종목, 둘러보기 탭, 전체 보기 | 홈 인기는 `GET /stocks/rankings?sort=MOST_FAVORITED&limit=3`, 둘러보기는 선택한 탭의 `sort`와 `limit=3` | 순위, 종목, 가격, 전일 대비, 선택한 탭의 거래량 또는 관심 등록 계정 수. 전체 보기는 같은 정렬의 첫 페이지부터 조회 |
| 홈 최근 공시 카드 | `GET /disclosures?limit=2` | 전체 상장기업의 최신 유효 D002 접수 2건. 카드의 접수번호로 상세 이동 |
| 홈 ‘임원들이 사고판 종목’ → 종목 상세 | `GET /insider-trades/rankings?side=BUY&limit=3` 또는 `side=SELL` → `GET /insider-trades` | 최근 30일 접수분의 장내 매수액·매도액 상위 종목, 거래 임원 수, 해당 순위를 구성한 거래 행. 6절 참조 |
| 검색창 진입, 기업명·코드 입력 | `GET /stocks`, `GET /stocks?q=삼성전자` | 조회 가능한 종목, 검색 결과 총건수, 종목별 가격과 기준 시각. ‘최근 공시한 기업’ 진입 화면은 최근 공시 응답을 기업별로 한 번씩 표시 |
| 종목 상세, 가격 차트, 임원 거래 공시 | `GET /stocks/{stockCode}`, `GET /stocks/{stockCode}/chart`, `GET /disclosures?stockCode=005930` | 최근 30일의 거래일별 종가를 날짜순으로 표시한다. 공시 막대와 카드는 같은 접수번호로 이동한다. |
| 주식 추적 → 기업 → 보유량 변화 또는 거래 유형 | `GET /disclosures?days=30&direction=ALL`, `GET /insider-trades?days=30&tradeType=ALL` | 상단 건수와 증감 목록은 보고서 단위, 거래 유형 목록은 원문 변동 행 단위. 6절 참조 |
| 주식 추적 → 국회의원, 기업 상세 → 의원 목록 | 연도 선택 후 `/assembly` 아래의 정당·종목·의원 조회 | 공개 당시 소속과 본인 명의 보유분으로 양방향 탐색. 신고 상세는 가족 신고를 별도로 표시한다. 7절 참조 |
| 공시 상세, 원문 보기 | `GET /disclosures/{receiptNo}` | 보고자·직책, 직전·이번 보유량, 변동 행, 단가와 시세의 비교, 원문 URL |
| 하트 등록·해제, 관심 종목 열기 | Firebase Auth + Firestore, `GET /stocks?codes=005930,000660` | 계정의 저장 목록을 구독하고 해당 코드의 시세를 한 번에 조회 |
| 마이, 서비스 안내 6개 화면 | Firebase Auth 사용자 정보, 앱 내 안내 문서 | 이름·이메일·로그인 상태, 소개·FAQ·출처·이용약관·개인정보처리방침·오픈소스 라이선스 |

### 목록 요청의 기본값

| 호출 | 파라미터 생략 시 | 페이지 크기와 정렬 |
|---|---|---|
| `GET /stocks` | 전체 상장 주식의 첫 페이지 | `limit=20`(1~50). 종목코드 오름차순. `q`를 입력하면 코드·종목명·법인명이 정확히 일치하는 결과를 먼저, 종목명 또는 법인명에 검색어가 포함된 결과를 다음에 표시한다. 각 그룹의 동률은 종목코드 오름차순이다. |
| `GET /stocks?codes=005930,000660` | 요청한 코드 일괄 조회 | 한 요청에 1~50개. 페이지를 나누지 않고 `nextCursor=null`을 반환한다. 요청 순서를 유지하고 중복은 첫 항목만, 미등록 코드는 결과에서 제외한다. 등록된 종목의 시세가 비었으면 종목은 포함한다. |
| `GET /stocks/rankings` | `sort=VOLUME`의 첫 페이지 | `limit=20`(1~50). 홈 둘러보기는 현재 선택한 정렬에 `limit=3`, 홈 인기 종목은 `sort=MOST_FAVORITED&limit=3`을 명시한다. 전체 보기는 같은 정렬의 첫 페이지를 요청한다. |
| `GET /disclosures` | 전체 기간, 모든 상장기업의 D002, `direction=ALL`의 첫 페이지 | `limit=20`(1~50). 공시 탭은 최초 요청에 `days=30`, 홈 카드는 `limit=2`를 명시한다. |

검색어는 앞뒤 공백을 제거하고 영문 대소문자를 구분하지 않는다. 빈 검색어는 전체 조회다. 종목코드는 앞의 0을 보존하는 6자리 문자열로 비교한다. `codes`와 `q`, `limit`, `cursor`를 함께 보내거나 코드 목록에 빈 항목을 넣으면 400이다. `total`은 검색 조건에 맞는 전체 종목 수이며 일괄 조회에서는 실제 반환한 고유 종목 수다.

검색·순위·공시 목록 모두 첫 호출에서 `cursor`를 생략한다. 다음 호출에는 받은 `nextCursor`와 동일한 검색·정렬·필터·페이지 크기를 사용한다. 서버는 같은 커서로 재시도하면 같은 페이지를 반환하며, 중간에 가격이나 하트 수가 바뀌어도 첫 페이지 시점의 순번과 항목을 유지한다. 마지막 페이지는 `nextCursor=null`이다. 필터·정렬을 바꾸면 앱은 첫 페이지부터 다시 조회한다.

| 요청 결과 | HTTP와 응답 |
|---|---|
| 조회 성공 | GET은 200과 JSON. 검색 결과 0건은 `{"total":0,"items":[],"nextCursor":null}`. |
| 요청 형식 오류 | 400 `INVALID_ARGUMENT`. 소수·범위 밖의 `limit`, 지원하지 않는 정렬·방향·기간, 잘못된 경로 식별자 형식, 해석할 수 없거나 다른 조건에 사용한 커서를 포함한다. |
| 상세 대상 없음 | 형식이 올바른 종목·접수번호가 저장소에 없으면 404 `NOT_FOUND`. 알려진 종목의 일부 값만 비어 있으면 200으로 종목과 미제공 상태를 돌려준다. |
| 페이지 또는 서비스 만료·실패 | 유효했던 커서의 데이터 버전을 더는 제공할 수 없으면 410 `CURSOR_EXPIRED`. 요청 전체를 일시적으로 처리할 수 없으면 503 `SERVICE_UNAVAILABLE`. 오류 본문은 4절의 공통 구조를 쓴다. |

## 2. 공시 목록과 상세

### 목록 계약

공시 목록의 `stockCode`는 숫자와 영문 대문자로 구성된 6자리 단축코드다. 앞의 0을 유지한다. 목록 원자료에서 종목코드가 비면 같은 DART 법인코드에 연결된 단축코드가 한 개일 때만 보완한다.

로컬 Kotlin 서버는 `collect` 명령으로 수집을 마친 뒤 게시한 공시 목록을 읽는다. 홈은 `GET /api/v1/disclosures?limit=2`, 공시 탭은 `GET /api/v1/disclosures?days=30`을 호출한다. [실행과 검증](../../backend/README.md)

| 입력 | 규칙 |
|---|---|
| `days` | 공시 탭은 7·30·90 중 하나를 명시하고 최초 선택은 30이다. 서버는 한국 날짜 기준 오늘을 포함한 최근 N일의 **접수일**로 조회한다. 생략 시 고정 시작일부터 누적한 유효 공시를 최신순으로 반환한다. 따라서 90일 필터에서 빠진 공시도 종목 상세에서 계속 조회할 수 있다. |
| `stockCode`, `direction` | 종목코드는 선택값이다. 지정하면 해당 종목의 발행사 공시를 조회한다. 같은 기업의 여러 종류주식에 연결된 공시는 전체 목록에서 접수번호당 한 번만 센다. 방향은 `ALL`(기본)·`INCREASE`·`DECREASE`다. 직전 보고 대비 보유 수량의 부호로 분류한다. 동일 수량과 미확인 보고서는 전체에서 볼 수 있다. |
| `limit`, `cursor` | 기본 20건, 1~50건. 접수일 내림차순, 동률이면 접수번호 내림차순이다. 다음 요청은 같은 조건과 서버가 준 불투명 커서를 사용한다. 커서는 첫 조회의 데이터 버전과 조건에 묶고 필터 변경 시 폐기한다. |

선택한 종목의 발행사 연결 또는 요청 기간의 목록 수집이 미완료면 `503 SERVICE_UNAVAILABLE`로 공시 영역에 재시도를 제공한다. 기간 생략은 서비스가 제공하는 완료된 이력 범위 전체를 뜻한다. 빈 목록과 0건은 조회 범위를 확인한 경우에만 반환한다. 원문 변동 행만 미확보면 목록·상세는 200으로 제공한다.

목록 응답의 `counts`는 기간·종목 조건에 맞는 전체 보고서를 방향 필터와 페이징 적용 전에 센다. `total = increase + decrease + unchanged + unknown`이다. `items`에는 현재 방향의 보고서만 들어간다. `period`는 양끝을 포함하며, 기간 제한이 없으면 null이다. 정정 보고서는 최신 유효본을 대표로 집계하고 `isCorrection`으로 표시한다. DART `last_reprt_at=Y`로 최종보고서를 확인하고, 전체 이력은 `N`으로 조회한다. `rm`의 ‘정’은 후속 정정 존재, ‘철’은 철회 표시다. 철회 건은 유효 목록·건수에서 제외한다. 이전 접수번호의 상세는 해당 원문으로 열며, `latestReceiptNo`는 관련 원문으로 연결을 확인한 경우만 제공한다. 회사·보고자·날짜만으로 대응 관계를 추정하지 않는다.

아래는 7월 31일을 기준으로 삼성전자 표본 한 건만 넣은 **개발용 목록 응답**이다. 운영 기간의 전체 공시 건수를 의미하지 않는다.

```json
{
  "period": {"from": "2026-07-02", "to": "2026-07-31"},
  "counts": {"total": 1, "increase": 1, "decrease": 0, "unchanged": 0, "unknown": 0},
  "items": [{
    "receiptNo": "20260731000755", "filedOn": "2026-07-31",
    "stockCode": "005930", "companyName": "삼성전자",
    "reporterName": "노태문", "position": "대표사장", "isCorrection": false,
    "holdingDeltaShares": 3045, "direction": "INCREASE",
    "tradePreview": [{"rowNo": 1, "reason": "장내매수", "changedOn": "2026-07-30", "deltaShares": 3045, "unitPriceKrw": 230000}],
    "tradeRowCount": 1
  }],
  "nextCursor": null
}
```

홈 카드는 `holdingDeltaShares`를 사용하고, 종목 상세의 거래 카드는 `tradePreview`를 쓴다. 미리보기는 상세와 같은 행 번호·순서로 최대 두 행을 제공한다. 전체 변동은 상세에서 확인한다. 보고서의 순증감과 개별 매수량은 서로 달라질 수 있다.

### 상세 계약

다음은 [노태문 공시 상세 시안](https://www.figma.com/design/vffh5rjHdioDiQrClJd0XX/StockTrace?node-id=87-1156)을 위한 응답 예시다. 거래 행은 원문 대조 기록을 사용했다. 직전·이번 보유량도 이 보고서 원문에서 확인했다. 직책·비율은 화면 예시값이고, 아래 종가와 거래일은 차이 계산을 보여주기 위한 테스트 값이다.

```json
{
  "receiptNo": "20260731000755", "filedOn": "2026-07-31", "reportAsOf": "2026-07-30",
  "stockCode": "005930", "companyName": "삼성전자", "reporterName": "노태문",
  "relationship": "등기임원", "position": "대표사장", "ownershipPercent": 0,
  "isCorrection": false, "latestReceiptNo": "20260731000755",
  "sourceUrl": "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260731000755",
  "holdings": {"previousShares": 121235, "currentShares": 124280, "deltaShares": 3045, "changePercent": 2.51},
  "comparisonQuote": {"stockCode": "005930", "securityType": "보통주", "priceKrw": 180000, "tradingDate": "2026-11-24", "asOf": null, "kind": "CLOSE"},
  "transactions": [{
    "rowNo": 1, "changedOn": "2026-07-30", "securityType": "보통주", "reason": "장내매수",
    "deltaShares": 3045, "unitPriceKrw": 230000,
    "priceComparison": {"status": "AVAILABLE", "differenceKrw": -50000, "differencePercent": -21.74}
  }]
}
```

| 상황 | 서버와 앱의 약속 |
|---|---|
| 직전 보고와 비교 | 같은 보고서의 소유상황 표에서 직전·이번 수량을 같은 열로 읽는다. 예를 들어 노태문 원문에는 121,235주 → 124,280주, 조영준 정정 원문에는 6,575주 → 9,460주로 기재돼 있다. `elestock`의 `sp_stock_lmp_irds_cnt`는 공시된 수량 증감이고 `sp_stock_lmp_irds_rate`는 공시 비율의 증감이다. 앱의 수량 변화율은 `deltaShares / previousShares × 100`으로 계산한다. 한쪽이 미확인이면 확인한 수량만 유지하고 증감·변화율은 null이다. 직전 수량이 0이면 증감은 계산하되 변화율은 null이다. 공시에 표기된 소유비율 0%는 그대로 보존한다. |
| 개별 거래의 가격 차이 | 동일 종목·주식 종류·가격 기준을 확인한 장내매수·매도 행에서 `differenceKrw = priceKrw − unitPriceKrw`, `differencePercent = differenceKrw / unitPriceKrw × 100`. 표시용 비율은 소수 둘째 자리로 반올림한다. 매도 행에는 ‘공시 매도 단가’라는 라벨을 사용한다. |
| 가격 비교 불가 | 시세 미수신, 단가 누락·0, 비교 대상 밖의 사유, 분할·병합 등에 따른 기준 불일치에는 행별 상태를 각각 `NO_QUOTE`, `NO_UNIT_PRICE`, `INELIGIBLE_REASON`, `BASIS_UNVERIFIED`로 전달하고 차이 두 값은 null이다. 보유량 비교와 가격 비교는 독립적으로 표시한다. |
| 같은 날 여러 단가 | 덴티스 공시 `20260727000407`의 7월 22일 장내매수 두 행은 `(2000, 2365)`, `(1200, 2480)`이다. 거래 요약은 ‘총 3,200주 · 주당 2,365~2,480원’으로 표시할 수 있다. 원문 행의 식별자는 `receiptNo + rowNo`이며 날짜로 합치지 않는다. |
| 정정·수증·자료 없음 | 정정 전후 비교표를 거래로 중복 집계하지 않는다. 수증은 원문 사유와 수량을 보존한다. 아직 원문 행을 확보하지 못해도 보고서 정보와 원문 링크는 제공한다. 이때 `tradeRowCount: null`, `tradePreview: []`, 상세의 `transactions: null`이다. 확인한 결과 대상 행이 0개면 각각 0과 빈 배열이다. |

## 3. 시세·검색·순위

| 자원 | 요청·응답 계약 |
|---|---|
| 공통 종목 시세 | `StockSummary { stockCode, stockName, companyName, quote }`. `quote { kind, asOf, fetchedAt, tradingDate, priceKrw, previousCloseKrw, changeKrw, changePercent, volumeShares, source }`. 종목코드는 앞의 0을 유지하는 문자열이다. `kind`는 `CLOSE / UNAVAILABLE`. 종가의 기준은 공급처의 `tradingDate`이며 일별 데이터의 `asOf`는 null이다. |
| 전일 대비·미수신 | 홈의 `changeKrw`, `changePercent`는 금융위원회 응답의 `vs`, `fltRt`를 각각 사용한다. 순위도 같은 거래일의 `fltRt`를 쓴다. 직전 거래일의 실제 종가는 `previousCloseKrw`로 별도 제공한다. 각 값이 누락되면 해당 값만 null로 두며 보합은 확인된 등락 0일 때만 표시한다. |
| 시장 지표 | `{ items: [{ symbol, value, unit, changeValue, changePercent, referenceDate, asOf, fetchedAt, source, status }] }`. `KOSPI / KOSDAQ`은 `POINT`, `USD_KRW`는 `KRW_PER_USD`. 지표별 `status`는 `AVAILABLE / DELAYED / UNAVAILABLE`. 홈은 지수에 `changePercent`, 환율에 `changeValue`를 사용한다. 일별 고시값은 기준일 `referenceDate`로 표시하며 관측 시각이 없는 경우 `asOf`는 null이다. |
| 종목 검색·일괄 조회 | 대상은 국내 상장 주식 전체다. 시세·공시 유무와 무관하게 검색한다. `q`는 앞뒤 공백 제거 후 이름 부분 일치 또는 6자리 코드 정확 일치. 빈 검색은 전체 대상, 정확 일치 우선 후 종목코드순. `codes`는 관심 목록용 쉼표 구분 코드 최대 50개이며 `q`와 함께 보낼 수 없다. 응답은 `{ total, items: StockSummary[], nextCursor }`. 0건도 정상 응답이다. |
| 종목 순위 | `sort=GAINERS / LOSERS / VOLUME / MOST_FAVORITED`. 대상은 전체 상장 주식이다. 상승은 양수 등락률 내림차순, 하락은 음수 등락률 오름차순, 거래량은 해당 거래일 누적 주수 내림차순이다. 해당 지표가 없는 종목은 그 순위에서 제외한다. 동률은 코드 오름차순. `{ asOf, items: [{ rank, stock, favoriteCount }], nextCursor }`. 인기 종목은 `MOST_FAVORITED&limit=3`을 재사용한다. |
| 인기 순위 집계 | 홈 인기 영역과 둘러보기의 ‘관심 등록순’ 탭은 같은 순위를 쓴다. 집계 시점에 해당 종목에 하트를 남겨 둔 계정 수(`favoriteCount`) 내림차순이며 기간 제한은 없다. 하트가 1개 이상인 종목만 포함하고 동률이면 종목코드 오름차순이다. 모두 0개이면 `items=[]`, `nextCursor=null`을 반환한다. |
| 종목 상세와 차트 | `GET /stocks/{stockCode}/chart`는 한국 날짜 기준 오늘과 직전 29일을 조회한다. 응답은 `{ stockCode, period: { from, to }, source, status, points: [{ date, priceKrw }] }`. 공급처가 종가를 제공한 거래일에만 한 점을 표시하고 날짜 오름차순으로 정렬한다. 자료가 없으면 `status=UNAVAILABLE, points=[]`다. |

삼성전자 시안에는 관측 가격과 다른 기준의 차트·전일 대비 예시가 섞여 있다. 실제 시세 화면은 동일 종목·거래일·가격 기준의 값으로 구성한다. 테스트용 차트는 ‘모의 데이터’로 표시한다.

### Android와 서버가 공유하는 응답 자료형

아래 표는 JSON 응답을 Kotlin DTO로 읽는 기준이다. `?`인 필드도 키를 포함하고 값이 없을 때 JSON `null`을 보낸다. 식별자와 열거값은 문자열, 날짜는 ISO 날짜 문자열, 시각은 오프셋이 있는 ISO 시각 문자열이다. 가격·비율 등 표의 십진수는 JSON number이며 서버 계산은 십진 정밀도를 유지한다.

| 응답 | 필수 필드와 자료형 | null 허용 필드 |
|---|---|---|
| 종목 상세·검색 항목 | `stockCode, stockName, companyName: String`, `quote: Quote`. 상세는 이 객체 자체를 반환하고 검색은 `items` 배열에 담는다. | 없음 |
| Quote | `kind: CLOSE 또는 UNAVAILABLE`, `source: String` | `priceKrw, previousCloseKrw, changeKrw, volumeShares: Long?`, `changePercent: 십진수?`, `tradingDate: 날짜?`, `asOf, fetchedAt: 시각?`. 종가가 있으면 `priceKrw`와 `tradingDate`는 필수다. `fetchedAt`은 마지막 성공 수집 시각이다. |
| 검색·순위 목록 | 검색의 `total: Int`. 두 목록 모두 `items: 배열`. 순위의 `asOf: 시각`은 순위 생성 시각이다. 순위 항목은 `rank: Int`(전체 결과에서 1부터 연속), `stock: StockSummary`. | `nextCursor: String?`. 순위 항목의 `favoriteCount: Long?`는 `MOST_FAVORITED`일 때만 수치다. |
| 시장 지표 | `items: 배열`. 심볼별로 한 행씩 `KOSPI, KOSDAQ, USD_KRW` 순서다. 각 행의 `symbol, unit, source, status: String`은 필수다. | `value, changeValue, changePercent: 십진수?`, `referenceDate: 날짜?`, `asOf, fetchedAt: 시각?`. 한 지표만 미수신이면 해당 행에 `UNAVAILABLE`을 넣는다. 기존 값으로 갱신 실패를 표시할 때는 `DELAYED`와 원래 기준일을 보낸다. |
| 30일 차트 | `stockCode, source: String`, `status: AVAILABLE 또는 UNAVAILABLE`, `period.from, period.to: 날짜`, `points: 배열`. 각 점은 `date: 날짜`, `priceKrw: Long`. | 없음. 종가가 한 건이면 점 하나, 없으면 빈 배열을 반환한다. |
| 공시 목록 | `counts`의 모든 값과 `rowNo`는 Int, `isCorrection`은 Boolean, `receiptNo`는 14자리 문자열이다. 항목의 `direction`은 `INCREASE / DECREASE / UNCHANGED / UNKNOWN`. `tradePreview`는 항상 배열이다. 목록에 들어가는 나머지 키는 2절 JSON 예시를 따른다. | `period, nextCursor, reporterName, position, holdingDeltaShares, tradeRowCount`. 수량은 Long, 행 수는 Int다. 원문 미확인 시 미리보기 행의 `changedOn, deltaShares, unitPriceKrw`도 null을 허용한다. |
| 공시 상세 | `holdings`는 항상 객체이며 수량은 Long, 변화율은 십진수다. `sourceUrl: String`은 원문 주소다. 거래 행의 `priceComparison`은 항상 객체이며 상태는 2절의 규칙을 따른다. | `reportAsOf, reporterName, relationship, position, ownershipPercent, latestReceiptNo, comparisonQuote, transactions`. 보유량 객체의 개별 값도 null을 허용한다. 상세 거래 행의 날짜·수량·단가도 미리보기와 같은 규칙이다. 비교 불가 시 `differenceKrw, differencePercent`는 null이다. 신고 단가·단가 차이는 십진수다. |

로컬 Kotlin 서버가 Firestore의 `users/{uid}/favorites/{stockCode}` 문서를 종목별로 세어 인기 순위를 만든다. 계정과 종목의 조합당 문서는 하나이므로 여러 기기에서 등록하거나 쓰기를 재시도해도 하트는 하나다. 등록이 저장되면 다음 첫 페이지 조회에서 1 증가하고, 해제가 저장되면 1 감소한다. 열어 둔 목록의 다음 페이지는 기존 순서를 유지한다. 공개 응답에는 집계값만 담는다.

**호출 예시** — 홈에서 거래량순 전체 보기를 누르면 `GET /api/v1/stocks/rankings?sort=VOLUME&limit=20`이다. 응답에 커서가 있으면 다음 요청에 같은 파라미터와 `cursor=<nextCursor>`를 보낸다. 급상승 탭을 누르면 `GET /api/v1/stocks/rankings?sort=GAINERS&limit=20`으로 새 목록을 시작한다.

### 외부 API 선정과 호출 명세

로컬 서버는 금융위원회에서 전체 상장 주식과 두 종합지수의 일별 시세를 받는다. 종목 가격과 공시 단가 비교에는 최근 제공된 종가와 그 거래일을 사용한다. 원/달러는 한국수출입은행, 공시는 OpenDART에서 수집한다. 외부 API 키는 로컬 Kotlin 서버에서 관리한다.

| StockTrace에서 쓰는 곳 | 공급처와 호출 | 입력 → 화면 데이터 |
|---|---|---|
| 전체 상장 주식의 종가·등락·거래량 | 금융위원회 `GET /1160100/GetStockSecuritiesInfoService_V2/getStockPriceInfo_V2` | `https://apis.data.go.kr`에서 `serviceKey`, `resultType=json`, 날짜 범위와 페이지를 지정한다. `srtnCd`로 매칭해 `basDt`, `clpr`, `vs`, `fltRt`, `trqu`를 사용한다. 홈에는 ‘거래일 종가’로 표기한다. 상세 차트는 `beginBasDt`·`endBasDt`와 `likeSrtnCd`로 조회한 뒤 정확한 `srtnCd`만 골라 페이지를 모두 읽고 `basDt`별 `clpr`를 날짜순으로 반환한다. 휴장일에는 점을 추가하지 않는다. [주식시세정보](https://www.data.go.kr/data/15094808/openapi.do) |
| 홈의 코스피·코스닥 종합지수 | 금융위원회 `GET /1160100/GetMarketIndexInfoService_V2/getStockMarketIndex_V2` | 같은 호스트·인증 방식으로 `idxNm`의 종합지수 두 건을 식별해 `clpr`, `fltRt`, `basDt`를 표시한다. 기준일이 같은 지수끼리 비교한다. [지수시세정보](https://www.data.go.kr/data/15094807/openapi.do) |
| 홈의 원/달러 환율 | 한국수출입은행 `GET https://oapi.koreaexim.go.kr/site/program/financial/exchangeJSON` | `authkey`, `searchdate=YYYYMMDD`, `data=AP01`. 성공 응답에서 `cur_unit=USD` 행의 `deal_bas_r`를 쉼표 제거 후 십진수로 읽는다. 직전 **고시일** 값과의 원화 차이를 표시한다. [공공데이터포털 등록·신청 안내](https://www.data.go.kr/data/3068846/openapi.do) |
| 전체 상장 주식 검색 목록 | 금융위원회 KRX상장종목정보 | 기준일별 모든 페이지에서 시장·단축코드·ISIN·종목명·법인명을 확보한다. 인증 응답에서 시장별 포함 범위와 단축코드 표기를 대조한다. [상장종목정보](https://www.data.go.kr/data/15094775/openapi.do) |
| 검색용 기업·종목 연결 | OpenDART `GET /api/corpCode.xml` | ZIP 안의 `corp_code`(8자리), `stock_code`(6자리), `corp_name`을 연결한다. 같은 발행사의 종류주식은 검증된 발행사 식별 정보로 연결하며, 시세는 종목코드별로 구분한다. [고유번호 명세](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019018) |
| 최근 공시와 보고 수량 | OpenDART `GET /api/list.json`, `GET /api/elestock.json` | 목록은 기업 필터 없이 접수일 구간별로 마지막 페이지까지 수집한다. `bgn_de`, `end_de`, `pblntf_detail_ty=D002`로 접수 건을 찾고, 소유보고는 `corp_code`로 조회한다. 접수번호를 연결해 보고자·수량·증감을 얻는다. [공시검색](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001) · [소유보고](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS004&apiId=2019022) |
| 공시 상세의 거래별 단가·수량 | OpenDART `GET /api/document.xml` | `rcept_no`로 원문 ZIP을 받아 세부변동내역을 읽는다. DB에 접수번호와 행 순번을 보관한다. 국내 DART 호출의 호스트는 `https://opendart.fss.or.kr`, 인증 파라미터는 `crtfc_key`다. [공시서류 원본 명세](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019003) |

**계정과 관심 목록**은 Android에서 Firebase Authentication의 Google 로그인과 Cloud Firestore SDK를 사용한다. **인기 종목**은 Firestore의 관심 등록 계정 수로 집계하고, 급상승·급하락·거래량순은 금융위원회 데이터에서 같은 거래일의 상장 종목을 정렬한다. 순위 표의 가격도 해당일 종가로 맞춘다. [Firebase 로그인](https://firebase.google.com/docs/auth/android/google-signin) · [Cloud Firestore](https://firebase.google.com/docs/firestore/quickstart)

### 무료 이용 범위와 수집 방식

- **금융위원회 일별 시세** — 기준일의 다음 영업일 오후 1시 이후에 갱신된다. 서버는 실제 제공된 최근 `basDt`를 보존한다. 주말이나 장 마감 직후에도 과거 종가의 날짜를 유지한다. 일별 값으로 장중 차트를 생성하지 않는다. [주식시세 갱신 안내](https://www.data.go.kr/data/15094808/openapi.do)
- **한국수출입은행** — 무료이고 공공데이터포털에 이용허락범위 제한 없음으로 등록돼 있다. 홈 라벨은 ‘원/달러 · 매매기준율’과 고시 기준일을 쓴다. 휴일이나 고시 전 빈 응답이면 최근 성공한 고시일을 유지한다. 이전 고시값이 없으면 등락만 null이다. 서버에서 고시일별로 공유하며, 포털이 호출 한도를 고정 수치로 제시하지 않아 발급 안내를 따른다. [공식 데이터 안내](https://www.data.go.kr/data/3068846/openapi.do)
- **OpenDART** — 서버에서 새 접수번호를 발견하면 원문을 한 번 수집하고 목록·상세가 이를 재사용한다. 공식 가이드는 통상 20,000건 이상 요청 시 제한 코드 `020`이 발생하고 키별 한도가 다를 수 있다고 안내한다. [공식 응답 코드](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS004&apiId=2019022)

| 함께 검토한 무료 후보 | StockTrace에서의 판단 |
|---|---|
| Yahoo Finance | 한국 종목 `.KS`와 `.KQ` 모두 20분 지연으로 안내한다. 여러 종목의 가격을 수동 대조할 때 참고한다. 무료 공식 한국 분봉 개발자 API는 이번 조사에서 확인하지 못했다. [거래소별 지연 시간](https://help.yahoo.com/kb/finance/article-exchanges-data-delays-sln2310.html) |
| Twelve Data · DataCedar | Twelve Data의 한국 주식은 개인 Pro 이상에서 종가(EOD)를 제공한다. DataCedar는 삼성전자 페이지에 확인 당시 저장된 분봉이 없다고 표기했다. [Twelve Data 시장별 범위](https://twelvedata.com/exchanges?country=South+Korea) · [DataCedar 삼성전자 수록 상태](https://datacedar.com/ko/markets/krx/005930) |
| Frankfurter | 인증키 없이 무료로 중앙은행 기준환율을 조회할 수 있다. 비교용 요청은 `GET https://api.frankfurter.dev/v2/rates?base=USD&quotes=KRW&providers=ECB`다. 국내 매매기준율과 기준이 달라 자동 전환하거나 두 공급처 값으로 전일 차이를 계산하지 않는다. [공식 명세·호출 정책](https://frankfurter.dev/) |

### DB 설계 전에 대조할 실제 응답

| 확인 대상 | 완료 기준 |
|---|---|
| 종가·차트·순위 | 삼성전자(005930)와 덴티스(261200)의 최근 30일을 같은 조건으로 조회한다. 거래일별 중복 제거 후 마지막 점이 상세 종가·거래일과 일치하는지, 같은 날짜의 등락률과 거래량이 순위에 반영되는지 대조한다. |
| 지수·환율 | 금융위원회 응답에서 코스피·코스닥 **종합**의 실제 지수명과 기준일을 확인한다. 한국수출입은행에서 USD 최근 두 고시일을 조회해 소수점과 전 고시일 대비 차액, 휴일 빈 응답을 대조한다. |
| 공시 이력·노출 빈도 | 전체 상장기업의 최근 90일 접수 전체를 페이지 끝까지 수집하고 7·30·90일 집계와 정정·철회 제외 결과를 대조한다. 저장한 조영준 정정 원문에서 수증과 장내매수 단가가 뒤바뀐 정정 사유와 합계 2,885주를 확인했다. 원공시와 정정 접수번호의 연결은 목록 API와 별도로 대조한다. |
| 공시 단가 비교 | 변동일부터 최근 종가일까지 동일 주식 종류·주당 가격 기준인지 확인한다. 분할·병합 여부를 검증하지 못한 표본은 `BASIS_UNVERIFIED`로 두고 두 원자료와 기준일만 표시한다. |

DART 표본 10건의 대표 행 14개를 대조한 기록과 위의 보유량 원문 확인은 완료했다. 금융위원회·수출입은행의 인증 응답과 상장시장별 종목 목록과 전체 D002 기간 집계는 아직 검증 전이다.

## 4. 계정, 오프라인과 오류

- **관심 등록** — 홈·검색에서 비로그인 사용자가 하트를 누르면 대상 코드를 보관하고 Google 로그인 성공 뒤 `users/{uid}/favorites/{stockCode}`에 `stockCode, createdAt`을 저장한다. 취소하면 쓰기 없이 원래 화면으로 돌아간다. 해제는 같은 문서를 삭제한다. 본인 UID 경로만 읽고 쓰도록 Firestore 규칙을 둔다. 마이의 로그아웃은 사용자 구독과 로컬 관심 표시를 해제한다.
- **저장 목록** — Firestore에는 관심 관계만 저장한다. 가격은 일괄 시세 조회 결과를 코드로 결합한다. 한 종목의 시세가 없어도 저장한 종목은 목록에 남기고 가격만 ‘시세 미제공’으로 표시한다.
- **오프라인** — Room에 마지막 성공 응답과 `cachedAt`을 저장한다. 홈의 ‘저장 시각’과 종가의 거래일 `tradingDate`를 구분한다. 검색 실패는 재시도 상태로 표시한다. 통신 실패를 검색 결과 0건으로 캐시하지 않는다.
- **페이지와 오류** — 정상 빈 목록은 HTTP 200과 빈 배열, 마지막 페이지는 `nextCursor: null`이다. 첫 페이지 실패는 전체 재시도, 추가 페이지 실패는 기존 행을 유지하고 같은 커서로 재시도한다. 첫 로딩·지연 안내는 앱 요청 상태로 처리한다. 기간·방향 변경 후 늦게 도착한 이전 응답은 버린다.
- **REST 공통 규격** — 날짜는 `YYYY-MM-DD`, 시각은 UTC 오프셋이 있는 ISO 8601, 수량은 부호 있는 정수, 비율은 백분율 단위다. 오류 본문은 예를 들어 `{"code":"INVALID_ARGUMENT","message":"조회 기간은 7일, 30일, 90일 중 선택해 주세요.","requestId":"example-1"}`다. 잘못된 조건은 400, 없는 종목·공시는 404, 일시적 전체 조회 실패는 503이다. 만료된 커서는 410으로 응답해 앱이 첫 페이지에서 갱신할 수 있게 한다.

## 5. 구현 순서와 확인 기준

1. **공시 목록·상세 DTO와 Compose 연결** — 노태문은 증가·매수, 양준철은 감소·매도, 덴티스는 같은 날 두 단가로 렌더링한다. 수량 미확인, 직전 수량 0, 필터 결과 0건, 다음 페이지 실패도 고정 응답으로 확인한다.
2. **DART 수집과 서버 저장** — 접수번호를 보고서 키로 사용하고 원문 행을 별도 저장한다. 접수일 필터와 정정 중복 제거를 검증한 뒤 고정 응답을 실제 조회로 교체한다.
3. **시세·시장 지표·순위 연결** — 데이터 제공 범위가 확인된 항목부터 연결한다. 미수신 종목의 공시는 계속 열 수 있어야 한다. 시장 가격과 신고 단가의 비교에는 가격 기준 일치 여부도 확인한다.
4. **계정과 로컬 저장 연결** — 하트 → 로그인 → 원래 종목 등록, 로그인 취소, 계정 전환, 오프라인 홈을 검증한다. 같은 종목에 두 계정이 하트를 등록하고 한 계정이 해제하면 새 인기 순위 응답의 수가 1 → 2 → 1로 바뀌는지 확인한다.

## 6. 기업의 보유량 변화와 장내거래

홈의 ‘임원들이 사고판 종목’은 매수 또는 매도 **금액**으로 정렬한다. 종목 둘러보기의 관심 등록순은 기존 하트 집계를 사용한다. 종목 로고는 모든 화면에서 같은 `stockCode`로 앱의 로고를 찾고, 이미지가 없으면 종목명 약칭을 표시한다. [홈 시안](https://www.figma.com/design/vffh5rjHdioDiQrClJd0XX/StockTrace?node-id=87-875)

| 호출 | 요청 | 응답과 정렬 |
|---|---|---|
| `GET /insider-trades/rankings` | `side=BUY`(기본) 또는 `SELL`. 최근 30일 접수분으로 고정. 홈은 `limit=3`. | `{period, asOf, items, nextCursor}`. 항목은 `{rank, stock: StockSummary, amountKrw, executiveCount}`. 금액 내림차순, 동률이면 종목코드 오름차순. 등락률은 같은 종목의 `stock.quote.changePercent`를 쓴다. |
| `GET /insider-trades` | `days=7/30/90`(기본 30), `tradeType=ALL/BUY/SELL`(기본 ALL), 선택적 `stockCode`. `scope=ALL_REPORTERS`가 기본. 홈 순위에서 진입할 때는 `scope=RANKED_EXECUTIVES`와 동일한 종목·유형·30일 조건을 전달한다. | `{period, items, nextCursor}`. 각 행은 `{receiptNo, rowNo, filedOn, stockCode, companyName, reporterName, position, reason, tradeType, changedOn, deltaShares, unitPriceKrw, amountKrw}`. 접수일·접수번호 내림차순, 원문 행 번호 오름차순. 행을 누르면 기존 공시 상세에서 해당 `rowNo`를 연다. |

- **기간과 페이지**. 두 호출 모두 한국 날짜 기준 접수일 양끝을 포함하며 `period={from,to}`다. 홈 순위에서 날짜가 바뀌어도 상세에서 기간을 재현하도록 두 호출에 선택적 `to=YYYY-MM-DD`를 제공한다. 기본은 오늘이며 홈에서 받은 `period.to`를 상세로 전달한다. 미래 날짜·지원하지 않는 필터 조합은 400이다. 기본 20건, 최소 1건, 최대 50건과 1절의 커서 규칙을 적용한다.
- **금액과 사람 수**. 최신 유효 D002의 원문에서 임원 자격, 해당 상장 주식 종류, 장내거래 사유, 수량 및 양의 원화 단가를 확인한 행을 사용한다. 행별 `amountKrw = abs(deltaShares) × unitPriceKrw`를 십진수로 합산한다. 매수·매도를 각각 양의 금액으로 집계하고, 한 종목에서 같은 사람이 여러 번 거래해도 해당 방향의 `executiveCount`는 한 명이다. 같은 발행사 안에서 보고자 식별을 확인한 행만 집계한다. 기관·임원 자격 미확인 주요주주, 수증·증여, 철회·정정 전 행은 대상에서 제외한다. 정정 시 유효본으로 다시 계산한다.
- **홈에서 상세로**. `scope=RANKED_EXECUTIVES`는 순위에 실제로 합산한 행만 돌려준다. 이때 `stockCode`, `days=30`, `tradeType=BUY` 또는 `SELL`이 필요하다. 원문 수집이 미완료여서 순위나 선택 구간의 거래 목록을 산출할 수 없으면 해당 영역에 503을 반환한다. 집계가 완료됐고 조건에 맞는 행이 없으면 200과 빈 배열이다.
- **주식 추적에서 필터 전환**. 초기 화면은 `/disclosures`의 기간별 `counts`와 `/insider-trades`의 거래 행을 함께 사용한다. 보유량 증가·감소를 누르면 `/disclosures?direction=INCREASE` 또는 `DECREASE`로 보고서 목록을 연다. 거래 유형을 누르면 `/insider-trades`로 전환하며 기간은 유지한다. `tradeType=ALL`은 수증 등 `OTHER` 행도 포함한다. 매수·매도는 원문 사유로 분류한다. 예컨대 수증만 기재된 보고서는 보유량 증가와 전체 거래에는 나타날 수 있다. 장내매수 검색에는 포함되지 않는다. 식별이 불완전한 일반 거래 행의 종목코드·거래일·단가·금액 등 미확인 값은 null이다. [기업 추적 시안](https://www.figma.com/design/vffh5rjHdioDiQrClJd0XX/StockTrace?node-id=103-1688)

호출 예로 홈 매수액 순에서 삼성전자를 누르고 순위 응답의 `period.to`가 `2026-11-25`이면 `GET /api/v1/insider-trades?stockCode=005930&days=30&to=2026-11-25&tradeType=BUY&scope=RANKED_EXECUTIVES`로 연다. 종가 기준일은 별도의 `quote.tradingDate`다.

검증 예로 덴티스 표본의 매수 두 행이 집계 자격을 충족하면 두 행의 기여액은 `2,000 × 2,365 + 1,200 × 2,480 = 7,706,000원`이고, 동일 임원의 기여 인원은 1명이다. 같은 수집 버전에서 홈 금액과 상세 거래 합계를 대조한다.

## 7. 국회의원 재산공개

로컬 서버는 아래 GET 여섯 경로에 2026·2025년 `EXAMPLE` 자료를 제공한다. 예시 의원 ID는 `ex-1`, `ex-2`, `ex-3`이며 `ex-3`은 두 연도 모두 신고 미연결이다. `ex-2`의 공개 당시 그룹은 2025년 `people-power`, 2026년 `reform`이다. 추적 제외 CSV와 외부 키 없이 같은 결과를 재현할 수 있다. 공식 자료의 게시 및 CSV 적재는 미연결 상태다.

2025·2026년 정기공개 자료는 연도별 CSV로 확보했다. 로컬 Kotlin 서버의 수집 입력으로 사용할 수 있으며 앱의 조회 계약은 아래와 같다. 상장주식 신고 행은 한 행에 여러 종목이 들어갈 수 있어 종목·명의별로 분리한 뒤 집계한다. 현재 대조 범위와 원천 파일은 [실제 데이터 연결 검증](./assembly-data-check.md)에 기록했다.

국회의원 탭은 **공개 연도**를 선택한다. 각 연도에 공개된 재산의 **기준일**, **공개 당시 소속 정당**, **신고 관계**를 함께 보존한다. 화면에 보이는 2026년·2025년과 가상 의원은 개발 예시다. 실제로 제공할 연도는 서버가 반환한다. [정당별 시안](https://www.figma.com/design/vffh5rjHdioDiQrClJd0XX/StockTrace?node-id=131-7469) · [재산 신고 비교 시안](https://www.figma.com/design/vffh5rjHdioDiQrClJd0XX/StockTrace?node-id=131-8159)

아래 경로 앞에는 `/api/v1/assembly`가 붙는다. 연도 목록을 제외한 모든 호출에 `year`가 필수다. 연도별 응답은 `year, referenceDate, dataMode`를 포함한다. `dataMode=OFFICIAL/EXAMPLE`이며 한 응답 안에 두 자료를 섞지 않는다. `referenceDate`는 원문의 기준일이다. 연도 변경 시 필터와 커서를 초기화한다.

| 화면 | GET 경로와 입력 | 응답 필드와 동작 |
|---|---|---|
| 공개 연도 선택 | `/years` | `items[{year, referenceDate, dataMode}]`, 최신 공개 연도순. 앱은 첫 연도를 선택하며 빈 배열이면 자료 준비 상태를 표시한다. |
| 정당별 탭 | `/parties?year=2026` | `items[{partyId, partyName, displayOrder, memberCount, stockCount, coverage}]`. 시안 순서로 주요 5개 정당과 ‘그 외·무소속’ 그룹을 반환한다. 정당 로고는 앱이 안정적인 그룹 ID로 선택한다. |
| 종목별 탭, 정당별 보유 종목 | `/stocks?year=2026`, 선택적 `partyId` | `items[{stockCode, stockName, holderCount, shares, previousShares, deltaShares}], coverage, nextCursor`. 본인 명의 보유만 합산. 보유 의원 수 내림차순, 동률이면 종목코드순. 정당 상세 상단의 소속 의원 수는 같은 연도의 정당 응답을 쓴다. |
| 종목별 보유 정당, 기업 상세의 국회의원 영역 | `/stocks/{stockCode}/parties?year=2026` | `stockCode, stockName, holderCount, shares, coverage, items[{partyId, partyName, holderCount, shares}]`. 해당 종목의 보유가 확인된 그룹만 정당 표시순으로 반환한다. 기업 상세는 같은 총인원을 쓰고 ‘의원 목록 보기’는 종목으로 의원 목록을 좁힌다. |
| 전체·정당 소속 의원 명단, 이름 검색, 종목 보유 의원 | `/members?year=2026`. 선택적 `q, partyId, stockCode` | `total, items[{memberId, name, partyId, partyName, reportStatus}], nextCursor`. 조건은 교집합이다. 이름은 앞뒤 공백 제거 후 접두 일치, 정확 일치 우선 후 이름·ID순. 명단은 신고 미연결 의원도 포함한다. 종목 필터를 주면 본인 보유가 확인된 의원만 반환한다. |
| 의원 보유 내역, 정당 화면의 신고서 열기 | `/members/{memberId}/holdings?year=2026` | `memberId, name, partyId, partyName, reportStatus, previousYear, sourceUrl, items`. 각 행은 `{stockCode, stockName, relationship, previousShares, shares, deltaShares, comparisonStatus}`. 정당 화면의 신고서 링크는 `/members?partyId=…&reportStatus=LINKED`로 조회한 의원 ID를 사용한다(동일한 `year` 필수). |

정당 ID와 의원 ID는 표시명과 독립적인 문자열이다. 수량 및 수량 차이는 Long, 의원·종목 수는 Int, 연도는 네 자리 정수다. 명단의 선택적 `reportStatus` 필터는 `LINKED/UNLINKED`를 받으며 생략하면 두 상태를 모두 조회한다.

**집계와 빈 상태**

- 공개 당시 소속을 확인해 연도별로 묶는다. 시안의 다섯 정당은 더불어민주당·국민의힘·조국혁신당·개혁신당·진보당이다. 다른 정당과 확인된 무소속은 나머지 한 그룹으로 합친다. 소속 미확인은 미확인으로 기록하고 정당 합계의 `coverage`에 반영한다. 의원 수는 `memberId`의 고유 개수, 종목 수는 확인한 국내 상장 종목코드의 고유 개수다.
- 본인 명의 합계와 의원별 가족 공개분을 구분한다. 의원 상세의 `relationship=SELF/SPOUSE/FAMILY` 중 `SELF`만 정당·종목 집계에 반영한다. 동일 종목의 본인 신고 행은 동일인·동일 주식 종류를 확인해 합친다. 가족 행은 원문에서 같은 보유자를 연결한 경우만 비교한다. 종목 연결이 불확실한 행은 상세에 원문 명칭과 `stockCode=null`로 남긴다.
- `previousYear`는 직전 비교 가능한 정기공개의 연도다. 두 공개분의 수량과 비교 기준을 확인하면 `deltaShares=shares−previousShares`이고 `comparisonStatus=INCREASE/DECREASE/UNCHANGED`다. 한쪽 수량이 미확인이면 해당 수량과 차이는 null, 상태는 `UNKNOWN`이다. 직전 연도에만 확인된 종목도 상세에 남긴다. 정당별 증감은 **각 연도의 당시 소속 기준 합계**를 비교하므로 입·탈당도 반영된다. 양쪽 집계 범위가 충분할 때만 차이를 제공한다.
- 집계 응답의 `coverage=COMPLETE/PARTIAL/UNAVAILABLE`은 선택 범위의 자료 상태다. 일부만 확인되면 확인분만 표시하고 ‘확인된 자료 기준’을 붙인다. 자료 전체가 미연결이면 집계 수치가 null이다. 검증이 끝난 범위의 0건만 숫자 0으로 표시한다. 의원은 명단에 있지만 신고가 미연결이면 200, `reportStatus=UNLINKED, sourceUrl=null, items=[]`다. 연결된 실제 원문은 `LINKED`와 출처 URL을 제공한다. 예시는 가상 신고에 대한 링크를 생성하지 않는다.
- 정당 여섯 그룹 및 의원 한 명의 신고 행은 한 응답에 담는다. 종목·의원 목록은 기본 20개, 1~50개와 공통 커서 규칙을 따른다. 이름 검색 결과 없음은 200과 빈 목록, 형식 오류는 400, 미제공 연도·없는 의원 ID·등록되지 않은 종목코드는 404, 일시적 저장소 오류는 503이다.

**자료 연결과 검증**. 의원 명단·소속 대조 후보는 국회사무처의 역대 국회의원 인적사항이고, 보유 내역의 원천은 국회공직자윤리위원회 재산공개 원문이다. 현재 소속을 과거 신고에 소급하지 않는다. 실제 연도별 원문, 공개 당시 소속, 본인·가족 관계 및 자료 재제공 조건을 대조한 뒤 해당 연도를 공식 데이터로 게시한다. 보유 수량을 제공하는 외부 API는 미확정이므로 로컬에서 검증한 공개자료를 가져오는 방식도 같은 내부 계약을 사용한다. [국회사무처 의원 인적사항](https://www.data.go.kr/data/79931/linkedData.do) · [공직윤리시스템 재산공개 안내](https://www.peti.go.kr/prptOptp.do)

**시안 대조용 예시**. 2026년 가상 신고의 삼성전자 본인 보유 1,240주와 680주는 종목별·정당별 화면에서 모두 2명·1,920주다. 배우자 보유 80주는 의원 상세에만 나타난다. 직전 공개에서 LG전자 320주, 이번 공개 수량 미확인인 행의 `deltaShares`는 null이다. 전체 검색, 정당 명단, 기업 상세에서 같은 의원을 열면 같은 연도의 동일 신고 응답을 사용한다.
