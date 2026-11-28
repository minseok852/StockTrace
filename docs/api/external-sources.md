# StockTrace 외부 데이터·API 출처

2026-11-27 기준. 앱의 ‘데이터 출처’ 화면과 기말 보고서에서 원자료 발행기관과 실제 사용한 가공 자료의 작성자를 함께 표기하기 위한 목록이다.

## 국회의원 보유 주식에 사용한 자료

| 공개 연도 | 원자료 | StockTrace가 실제로 읽은 자료 | 로컬 산출물 |
|---|---|---|---|
| 2025 | 국회공직자윤리위원회, 2025-03-27 공개, 2024-12-31 기준. [국회공보 제2025-51호](https://www.assembly.go.kr/portal/cnts/cntsCont/dataA.do?cntsDivCd=NAMGZN&menuNo=601019&pdfClsCd=CPR&pdfId=379578) | 정보공개센터가 국회공보를 정리한 Google Sheets의 `상세` 탭. [저장소 opengirok/congress_asset_disclosure](https://github.com/opengirok/congress_asset_disclosure) · [사용한 시트](https://docs.google.com/spreadsheets/d/182m4MFFj4Ho2cICo3PGy8RyxHZ3vwNklyo5yM4M5M4Q/edit#gid=1164580719) | 상장주식 신고 254행. [2025년 CSV](../assembly-data/2025-listed-stock-rows.csv) |
| 2026 | 국회공직자윤리위원회, 2026-03-26 공개, 2025-12-31 기준. [국회공보 제2026-54호](https://www.assembly.go.kr/portal/cnts/cntsCont/dataA.do?cntsDivCd=NAMGZN&menuNo=601019&pdfClsCd=CPR&pdfId=379837) | GitHub 사용자 `yongkyu4803`가 국회공보를 파싱한 `data/source/재산공개_파싱.csv`. [저장소 yongkyu4803/NA_AssetReview](https://github.com/yongkyu4803/NA_AssetReview) · [사용한 커밋의 CSV](https://github.com/yongkyu4803/NA_AssetReview/blob/52f081965bbf0e96ea076661c84c6ef7e720b976/data/source/재산공개_파싱.csv) | 상장주식 신고 247행. [2026년 CSV](../assembly-data/2026-listed-stock-rows.csv) |

**보고서용 표기 문구**

> 국회의원 보유 주식의 원자료는 국회공직자윤리위원회의 2025년·2026년 정기 재산공개다. StockTrace는 2025년 공개분에 정보공개센터의 국회고위공직자 재산정보 공개 시트를, 2026년 공개분에 yongkyu4803의 NA_AssetReview CSV를 사용했다. 각 자료에서 국회의장·부의장·국회의원의 상장주식 신고 행을 추출했다.

두 CSV는 로컬 검증용으로 Git 추적에서 제외했다. 2025년 시트 안내에는 출처 표시와 비영리 이용, 2차적 저작물 작성 제한이 기재돼 있다. 2026년 CSV의 재배포 허락은 확인되지 않았다. [2025년 시트 이용 안내](https://docs.google.com/spreadsheets/d/182m4MFFj4Ho2cICo3PGy8RyxHZ3vwNklyo5yM4M5M4Q/edit#gid=438404318) · [표본 대조와 검증 범위](./assembly-data-check.md)

## 기업 공시·시세·환율에 사용하는 외부 서비스

| 제공기관과 자료 | 용도 | 현재 상태 |
|---|---|---|
| 금융감독원 OpenDART · [공시서류 원본](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019003) | 임원·주요주주의 거래 사유, 수량, 주당 단가 | 공시 표본 10건의 대표 행 14개 대조. [검증 기록](./공시-단가-검증.md) |
| 금융감독원 OpenDART · [고유번호](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019018) · [공시검색](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001) · [임원·주요주주 소유보고](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS004&apiId=2019022) | 발행사와 종목 연결, D002 접수 목록과 보고 수량 수집 | 수집 설계에 반영. 시장 전체 집계 검증 예정 |
| 금융위원회 · [KRX상장종목정보](https://www.data.go.kr/data/15094775/openapi.do) · [주식시세정보](https://www.data.go.kr/data/15094808/openapi.do) · [지수시세정보](https://www.data.go.kr/data/15094807/openapi.do) | 국내 종목 검색, 최근 30일 종가 차트, 거래량·등락률 순위, 코스피·코스닥 지수 | 지수 어댑터 연결. 로컬 대역으로 종합지수 선택, 기준일·종가·등락률 매핑 검증. 실제 인증 응답과 지수명 검증 예정 |
| 한국수출입은행 · [현재환율 API](https://www.data.go.kr/data/3068846/openapi.do) | 홈의 원/달러 매매기준율과 직전 고시일 대비 차이 | 서버 어댑터 연결. 로컬 응답 대역으로 USD 행, 쉼표·소수점, 휴일 빈 배열과 직전 고시일 차액 검증. 실제 인증 응답 미검증 |
| 국회사무처 · [역대 국회의원 인적사항](https://www.data.go.kr/data/79931/linkedData.do) | 의원 식별과 공개 당시 정당 대조 | 연도별 명단 연결 후보 |

요청 경로, 필드 매핑과 오류 처리 기준은 [내부·외부 API 명세](./api-design.md)에 있다.
